package mill
package javalib

import coursier.core.{Configuration, DependencyManagement}
import mill.api.{DefaultTaskModule, ExternalModule, Task}
import mill.api.PathRef
import mill.api.Result
import mill.util.{JarManifest, Secret, PossiblySecret, Tasks}
import mill.javalib.PublishModule.checkSonatypeCreds
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.javalib.publish.{Artifact, SonatypePublisher}
import os.Path
import mill.api.BuildCtx

/**
 * Configuration necessary for publishing a Scala module to Maven Central or similar
 */
trait PublishModule extends JavaModule { outer =>
  import mill.javalib.publish.*

  override def moduleDeps: Seq[PublishModule] = super.moduleDeps.map {
    case m: PublishModule => m
    case other =>
      throw new Exception(
        s"PublishModule moduleDeps need to be also PublishModules. $other is not a PublishModule"
      )
  }

  override def bomModuleDeps: Seq[BomModule & PublishModule] = super.bomModuleDeps.map {
    case m: (BomModule & PublishModule) => m
    case other =>
      throw new Exception(
        s"PublishModule bomModuleDeps need to be also PublishModules. $other is not a PublishModule"
      )
  }

  /**
   * The packaging type. See [[PackagingType]] for specially handled values.
   */
  def pomPackagingType: String =
    this match {
      case _: BomModule => PackagingType.Pom
      case _ => PackagingType.Jar
    }

  /**
   * POM parent project.
   *
   * @see [[https://maven.apache.org/guides/introduction/introduction-to-the-pom.html#Project_Inheritance Project Inheritance]]
   */
  def pomParentProject: T[Option[Artifact]] = None

  /**
   * Configuration for the `pom.xml` metadata file published with this module
   */
  def pomSettings: T[PomSettings]

  /**
   * The artifact version that this module would be published as
   */
  def publishVersion: T[String]

  /**
   * Optional information about the used version scheme.
   * This may enable dependency resolvers to properly resolve version ranges and version mismatches (conflicts).
   * This information will be written as `info.versionScheme` property in the `pom.xml`.
   * See [[VersionScheme]] for possible values.
   *
   * You can find more info under these links:
   * - https://docs.scala-lang.org/overviews/core/binary-compatibility-for-library-authors.html#recommended-versioning-scheme
   * - https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html
   * - https://www.scala-sbt.org/1.x/docs/Publishing.html#Version+scheme
   * - https://semver.org
   *
   * @since Mill after 0.10.0-M5
   */
  def versionScheme: T[Option[VersionScheme]] = Task { None }

  def publishMvnDeps
      : Task[(Map[coursier.core.Module, String], DependencyManagement.Map) => Seq[Dependency]] =
    Task.Anon {
      (rootDepVersions: Map[coursier.core.Module, String], bomDepMgmt: DependencyManagement.Map) =>
        val bindDependency0 = bindDependency()
        val resolvePublishDependency0 = resolvePublishDependency.apply()

        // Ivy doesn't support BOM, so we try to add versions and exclusions from BOMs
        // to the dependencies themselves.
        def process(dep: mill.javalib.Dep) = {
          var dep0 = bindDependency0(dep).dep

          if (dep0.version.isEmpty)
            for (version <- rootDepVersions.get(dep0.module))
              dep0 = dep0.withVersion(version)

          for (
            values <- bomDepMgmt.get(DependencyManagement.Key.from(dep0))
            if values.minimizedExclusions.nonEmpty
          )
            dep0 = dep0.withMinimizedExclusions(
              dep0.minimizedExclusions.join(values.minimizedExclusions)
            )

          resolvePublishDependency0(BoundDep(dep0, force = false).toDep)
        }

        val ivyPomDeps = allMvnDeps().map(process)

        val runIvyPomDeps = runMvnDeps().map(process)
          .filter(!ivyPomDeps.contains(_))

        val compileIvyPomDeps = compileMvnDeps().map(process)
          .filter(!ivyPomDeps.contains(_))

        val modulePomDeps = Task.sequence(moduleDepsChecked.collect {
          case m: PublishModule => m.artifactMetadata
        })()
        val compileModulePomDeps = Task.sequence(compileModuleDepsChecked.collect {
          case m: PublishModule => m.artifactMetadata
        })()
        val runModulePomDeps = Task.sequence(runModuleDepsChecked.collect {
          case m: PublishModule => m.artifactMetadata
        })()

        ivyPomDeps ++
          compileIvyPomDeps.map(_.copy(scope = Scope.Provided)) ++
          runIvyPomDeps.map(_.copy(scope = Scope.Runtime)) ++
          modulePomDeps.map(Dependency(_, Scope.Compile)) ++
          compileModulePomDeps.map(Dependency(_, Scope.Provided)) ++
          runModulePomDeps.map(Dependency(_, Scope.Runtime))
    }

  def publishXmlDeps: Task[Seq[Dependency]] = Task.Anon {
    val ivyPomDeps =
      allMvnDeps()
        .map(resolvePublishDependency.apply().apply(_))

    val runIvyPomDeps = runMvnDeps()
      .map(resolvePublishDependency.apply().apply(_))
      .filter(!ivyPomDeps.contains(_))

    val compileIvyPomDeps = compileMvnDeps()
      .map(resolvePublishDependency.apply().apply(_))
      .filter(!ivyPomDeps.contains(_))

    val modulePomDeps = Task.sequence(moduleDepsChecked.collect {
      case m: PublishModule => m.artifactMetadata
    })()
    val compileModulePomDeps = Task.sequence(compileModuleDepsChecked.collect {
      case m: PublishModule => m.artifactMetadata
    })()
    val runModulePomDeps = Task.sequence(runModuleDepsChecked.collect {
      case m: PublishModule => m.artifactMetadata
    })()

    ivyPomDeps ++
      compileIvyPomDeps.map(_.copy(scope = Scope.Provided)) ++
      runIvyPomDeps.map(_.copy(scope = Scope.Runtime)) ++
      modulePomDeps.map(Dependency(_, Scope.Compile)) ++
      compileModulePomDeps.map(Dependency(_, Scope.Provided)) ++
      runModulePomDeps.map(Dependency(_, Scope.Runtime))
  }

  /**
   * BOM dependency to specify in the POM
   */
  def publishXmlBomDeps: Task[Seq[Dependency]] = Task.Anon {
    val fromBomMods = Task.traverse(
      bomModuleDepsChecked.collect { case p: PublishModule => p }
    )(_.artifactMetadata)().map { a =>
      Dependency(a, Scope.Import)
    }
    Seq(fromBomMods*) ++
      bomMvnDeps().map(resolvePublishDependency.apply().apply(_))
  }

  /**
   * Dependency management to specify in the POM
   */
  def publishXmlDepMgmt: Task[Seq[Dependency]] = Task.Anon {
    depManagement().map(resolvePublishDependency.apply().apply(_))
  }

  def pom: T[PathRef] = Task {
    val pom = Pom(
      artifactMetadata(),
      publishXmlDeps(),
      artifactId(),
      pomSettings(),
      publishProperties(),
      packagingType = pomPackagingType,
      parentProject = pomParentProject(),
      bomDependencies = publishXmlBomDeps(),
      dependencyManagement = publishXmlDepMgmt()
    )
    val pomPath = Task.dest / s"${artifactId()}-${publishVersion()}.pom"
    os.write.over(pomPath, pom)
    PathRef(pomPath)
  }

  /**
   * Path to the ivy.xml file for this module
   */
  def ivy: T[PathRef] = Task {
    val content = ivy(hasJar = pomPackagingType != PackagingType.Pom)()
    val ivyPath = Task.dest / "ivy.xml"
    os.write.over(ivyPath, content)
    PathRef(ivyPath)
  }

  /**
   * ivy.xml content for this module
   *
   * @param hasJar Whether this module has a JAR or not
   * @return
   */
  private def ivy(hasJar: Boolean): Task[String] = Task.Anon {
    val dep = coursierDependency.withConfiguration(Configuration.runtime)
    val resolution = millResolver().resolution(Seq(BoundDep(dep, force = false)))

    val (results, bomDepMgmt) =
      (
        resolution.finalDependenciesCache.getOrElse(
          dep,
          sys.error(
            s"Should not happen - could not find root dependency $dep in Resolution#finalDependenciesCache"
          )
        ),
        resolution.projectCache
          .get(dep.moduleVersion)
          .map(_._2.overrides.flatten.toMap)
          .getOrElse {
            sys.error(
              s"Should not happen - could not find root dependency ${dep.moduleVersion} in Resolution#projectCache"
            )
          }
      )
    val publishXmlDeps0 = {
      val rootDepVersions = results.map(_.moduleVersion).toMap
      publishMvnDeps.apply().apply(rootDepVersions, bomDepMgmt)
    }
    val overrides = {
      val bomDepMgmt0 = {
        // Ensure we don't override versions of root dependencies with overrides from the BOM
        val rootDepsAdjustment = publishXmlDeps0.iterator.flatMap { dep =>
          val key = coursier.core.DependencyManagement.Key(
            coursier.core.Organization(dep.artifact.group),
            coursier.core.ModuleName(dep.artifact.id),
            coursier.core.Type.jar,
            coursier.core.Classifier.empty
          )
          bomDepMgmt.get(key).flatMap { values =>
            if (values.version.nonEmpty && values.version != dep.artifact.version)
              Some(key -> values.withVersion(""))
            else
              None
          }
        }
        bomDepMgmt ++ rootDepsAdjustment
      }
      lazy val moduleSet = publishXmlDeps0.map(dep => (dep.artifact.group, dep.artifact.id)).toSet
      val depMgmtEntries = processedDependencyManagement(
        depManagement().toSeq
          .map(bindDependency())
          .map(_.dep)
          .filter(_.version.nonEmpty)
          .filter { depMgmt =>
            // Ensure we don't override versions of root dependencies with overrides from the BOM
            !moduleSet.contains((depMgmt.module.organization.value, depMgmt.module.name.value))
          }
      )
      val entries = coursier.core.DependencyManagement.add(
        Map.empty,
        depMgmtEntries ++ bomDepMgmt0
          .filter {
            case (key, _) =>
              // Ensure we don't override versions of root dependencies with overrides from the BOM
              !moduleSet.contains((key.organization.value, key.name.value))
          }
      )
      entries.toVector
        .map {
          case (key, values) =>
            Ivy.Override(
              key.organization.value,
              key.name.value,
              values.version
            )
        }
        .sortBy(value => (value.organization, value.name, value.version))
    }
    Ivy(artifactMetadata(), publishXmlDeps0, extraPublish(), overrides, hasJar = hasJar)
  }

  def artifactMetadata: T[Artifact] = Task {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def defaultPublishInfos: T[Seq[PublishInfo]] = {
    def defaultPublishJars: Task[Seq[(PathRef, PathRef => PublishInfo)]] = {
      pomPackagingType match {
        case PackagingType.Pom => Task.Anon(Seq())
        case _ => Task.Anon(Seq(
            (jar(), PublishInfo.jar)
          ))
      }
    }
    Task {
      defaultPublishJars().map { case (jar, info) => info(jar) }
    }
  }

  /**
   * Extra artifacts to publish.
   */
  def extraPublish: T[Seq[PublishInfo]] = Task { Seq.empty[PublishInfo] }

  /**
   * Properties to be published with the published pom/ivy XML.
   * Use `super.publishProperties() ++` when overriding to avoid losing default properties.
   * @since Mill after 0.10.0-M5
   */
  def publishProperties: T[Map[String, String]] = Task {
    versionScheme().map(_.toProperty).toMap
  }

  /**
   * Publish artifacts to a local ivy repository.
   * @param localIvyRepo The local ivy repository.
   *                     If not defined, the default resolution is used (probably `$HOME/.ivy2/local`).
   * @param sources whether to generate and publish a sources JAR
   * @param doc whether to generate and publish a javadoc JAR
   * @param transitive if true, also publish locally the transitive module dependencies of this module
   *                   (this includes the runtime transitive module dependencies, but not the compile-only ones)
   */
  def publishLocal(
      localIvyRepo: String = null,
      sources: Boolean = true,
      doc: Boolean = true,
      transitive: Boolean = false
  ): Task.Command[Unit] = Task.Command {
    publishLocalTask(
      Task.Anon {
        Option(localIvyRepo).map(os.Path(_, BuildCtx.workspaceRoot))
      },
      sources,
      doc,
      transitive
    )()
    ()
  }

  /**
   * Publish artifacts the local ivy repository.
   */
  def publishLocalCached: T[Seq[PathRef]] = Task {
    val res = publishLocalTask(
      Task.Anon(None),
      sources = true,
      doc = true,
      transitive = false
    )()
    res.map(p => PathRef(p).withRevalidateOnce)
  }

  private def publishLocalTask(
      localIvyRepo: Task[Option[os.Path]],
      sources: Boolean,
      doc: Boolean,
      transitive: Boolean
  ): Task[Seq[Path]] =
    if (transitive) {
      val publishTransitiveModuleDeps = (transitiveModuleDeps ++ transitiveRunModuleDeps).collect {
        case p: PublishModule => p
      }
      Task.traverse(publishTransitiveModuleDeps.distinct) { publishMod =>
        publishMod.publishLocalTask(localIvyRepo, sources, doc, transitive = false)
      }.map(_.flatten)
    } else {
      val sourcesJarOpt =
        if (sources) Task.Anon(Some(PublishInfo.sourcesJar(sourceJar())))
        else Task.Anon(None)
      val docJarOpt =
        if (doc) Task.Anon(Some(PublishInfo.docJar(docJar())))
        else Task.Anon(None)

      Task.Anon {
        val publisher = localIvyRepo() match {
          case None => LocalIvyPublisher
          case Some(path) => new LocalIvyPublisher(path)
        }
        val publishInfos =
          defaultPublishInfos() ++ sourcesJarOpt().toSeq ++ docJarOpt().toSeq ++ extraPublish()
        publisher.publishLocal(
          pom = pom().path,
          ivy = Right(ivy().path),
          artifact = artifactMetadata(),
          publishInfos = publishInfos
        )
      }
    }

  /**
   * Publish artifacts to a local Maven repository.
   * @param m2RepoPath The path to the local repository  as string (default: `$HOME/.m2/repository`).
   *                   If not set, falls back to `maven.repo.local` system property or `~/.m2/repository`
   * @return [[PathRef]]s to published files.
   */
  def publishM2Local(m2RepoPath: String = null): Task.Command[Seq[PathRef]] = m2RepoPath match {
    case null => Task.Command { publishM2LocalTask(Task.Anon { publishM2LocalRepoPath() })() }
    case p =>
      Task.Command { publishM2LocalTask(Task.Anon { os.Path(p, BuildCtx.workspaceRoot) })() }
  }

  /**
   * Publish artifacts to the local Maven repository.
   * @return [[PathRef]]s to published files.
   */
  def publishM2LocalCached: T[Seq[PathRef]] = Task {
    publishM2LocalTask(publishM2LocalRepoPath)()
  }

  /**
   * The default path that [[publishM2Local]] should publish its artifacts to.
   * Defaults to `~/.m2/repository`, but can be configured by setting the
   * `maven.repo.local` JVM property
   */
  def publishM2LocalRepoPath: Task[os.Path] = Task.Input {
    sys.props.get("maven.repo.local").map(os.Path(_))
      .getOrElse(os.Path(os.home / ".m2", BuildCtx.workspaceRoot)) / "repository"
  }

  private def publishM2LocalTask(m2RepoPath: Task[os.Path]): Task[Seq[PathRef]] = Task.Anon {
    val path = m2RepoPath()
    val publishInfos = defaultPublishInfos() ++
      Seq(
        PublishInfo.sourcesJar(sourceJar()),
        PublishInfo.docJar(docJar())
      ) ++
      extraPublish()

    new LocalM2Publisher(path)
      .publish(pom().path, artifactMetadata(), publishInfos)
      .map(PathRef(_).withRevalidateOnce)
  }

  /** @see [[PublishModule.sonatypeLegacyOssrhUri]] */
  def sonatypeLegacyOssrhUri: String = PublishModule.sonatypeLegacyOssrhUri

  /** @see [[PublishModule.sonatypeCentralSnapshotUri]] */
  def sonatypeCentralSnapshotUri: String = PublishModule.sonatypeCentralSnapshotUri

  def publishArtifacts: T[PublishModule.PublishData] = {
    val baseNameTask: Task[String] = Task.Anon { s"${artifactId()}-${publishVersion()}" }
    val defaultPayloadTask: Task[Seq[(PathRef, String)]] = (pomPackagingType, this) match {
      case (PackagingType.Pom, _) => Task.Anon {
          val baseName = baseNameTask()
          Seq(
            pom() -> s"$baseName.pom"
          )
        }
      case (PackagingType.Jar, _) | _ => Task.Anon {
          val baseName = baseNameTask()
          Seq(
            jar() -> s"$baseName.jar",
            sourceJar() -> s"$baseName-sources.jar",
            docJar() -> s"$baseName-javadoc.jar",
            pom() -> s"$baseName.pom"
          )
        }
    }
    Task {
      val baseName = baseNameTask()
      PublishModule.PublishData(
        meta = artifactMetadata(),
        payload = defaultPayloadTask() ++ extraPublish().map(p =>
          (p.file, s"$baseName${p.classifierPart}.${p.ext}")
        )
      )
    }
  }

  /**
   * Publish all given artifacts to legacy OSSRH Sonatype (deprecated, use Sonatype Central publishing instead).
   *
   * Uses environment variables MILL_SONATYPE_USERNAME and MILL_SONATYPE_PASSWORD as
   * credentials.
   *
   * @param sonatypeCreds Sonatype credentials in format username:password.
   *                      If specified, environment variables will be ignored.
   *                      <i>Note: consider using environment variables over this argument due
   *                      to security reasons.</i>
   * @param gpgArgs       GPG arguments. Defaults to [[PublishModule.defaultGpgArgsForPassphrase]].
   *                      Specifying this will override/remove the defaults.
   *                      Add the default args to your args to keep them.
   */
  def publish(
      sonatypeCreds: String = "",
      signed: Boolean = true,
      gpgArgs: String = "",
      release: Boolean = true,
      readTimeout: Int = 30 * 60 * 1000,
      connectTimeout: Int = 30 * 60 * 1000,
      awaitTimeout: Int = 30 * 60 * 1000,
      stagingRelease: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val (contents, artifact) = publishArtifacts().withConcretePath
    val gpgArgs0 = PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
      Task.env,
      PublishModule.GpgArgs.fromUserProvided(gpgArgs)
    )
    new SonatypePublisher(
      uri = sonatypeLegacyOssrhUri,
      snapshotUri = sonatypeCentralSnapshotUri,
      checkSonatypeCreds(sonatypeCreds)(),
      signed,
      gpgArgs0,
      readTimeout,
      connectTimeout,
      Task.log,
      BuildCtx.workspaceRoot,
      Task.env,
      awaitTimeout,
      stagingRelease
    ).publish(contents, artifact, release)
  }

  override def manifest: T[JarManifest] = Task {
    import java.util.jar.Attributes.Name
    val pom = pomSettings()
    super.manifest().add(
      Name.IMPLEMENTATION_TITLE.toString() -> artifactName(),
      Name.IMPLEMENTATION_VERSION.toString() -> publishVersion(),
      Name.IMPLEMENTATION_VENDOR.toString() -> pom.organization,
      "Description" -> pom.description,
      "URL" -> pom.url,
      "Licenses" -> pom.licenses.map(l => s"${l.name} (${l.id})").mkString(",")
    )
  }
}

object PublishModule extends ExternalModule with DefaultTaskModule {
  def defaultTask(): String = "publishAll"
  val defaultGpgArgs: Seq[PossiblySecret[String]] = defaultGpgArgsForPassphrase(passphrase = None)

  /**
   * Imports a Base64 encoded GPG secret, if one is provided in the environment.
   *
   * @return Some(Right(the key ID of the imported secret)), Some(Left(error message)) if the import failed, None if
   *         the environment variable is not set.
   */
  def pgpImportSecretIfProvided(env: Map[String, String]): Option[Either[String, String]] = {
    for (secret <- env.get(EnvVarPgpSecretBase64)) yield {
      pgpImportSecret(secret).left.map { errorLines =>
        s"""Could not import PGP secret from environment variable '$EnvVarPgpSecretBase64'. gpg output:
           |
           |${errorLines.mkString("\n")}""".stripMargin
      }
    }
  }

  /** Imports a Base64 encoded GPG secret, if one is provided in the environment. Throws if the import fails. */
  def pgpImportSecretIfProvidedOrThrow(env: Map[String, String]): Option[String] =
    pgpImportSecretIfProvided(env).map(_.fold(
      err => throw new IllegalArgumentException(err),
      identity
    ))

  /**
   * Imports a Base64 encoded GPG secret.
   *
   * @return Right(the key ID of the imported secret), or Left(gnupg output) if the import failed.
   */
  def pgpImportSecret(secretBase64: String): Either[Vector[String], String] = {
    val cmd = Seq(
      "gpg",
      "--import",
      "--no-tty",
      "--batch",
      "--yes",
      // Use the machine parseable output format and send it to stdout.
      "--with-colons",
      "--status-fd",
      "1"
    )
    println(s"Running ${cmd.iterator.map(pprint.Util.literalize(_)).mkString(" ")}")
    val res = os.call(cmd, stdin = java.util.Base64.getDecoder.decode(secretBase64))
    val outLines = res.out.lines()
    val importRegex = """^\[GNUPG:\] IMPORT_OK \d+ (\w+)""".r
    outLines.collectFirst { case importRegex(key) => key }.toRight(outLines)
  }

  def defaultGpgArgsForPassphrase(passphrase: Option[GpgKey]): Seq[PossiblySecret[String]] = {
    passphrase.iterator.flatMap(_.gpgArgs).toSeq ++ Seq(
      "--no-tty",
      "--pinentry-mode",
      "loopback",
      "--batch",
      "--yes",
      "--armor",
      "--detach-sign"
    )
  }

  def pgpImportSecretIfProvidedAndMakeGpgArgs(
      env: Map[String, String],
      providedGpgArgs: GpgArgs.UserProvided
  ): GpgArgs = {
    val maybeKeyId = pgpImportSecretIfProvidedOrThrow(env)
    println(maybeKeyId match {
      case Some(keyId) => s"Imported GPG key with ID '$keyId'"
      case None => "No GPG key was imported."
    })
    makeGpgArgs(env, maybeKeyId, providedGpgArgs)
  }

  def makeGpgArgs(
      env: Map[String, String],
      maybeKeyId: Option[String],
      providedGpgArgs: GpgArgs.UserProvided
  ): GpgArgs = {
    if (providedGpgArgs.args.nonEmpty) providedGpgArgs
    else {
      val maybePassphrase = GpgKey.createFromEnvVarsOrThrow(
        maybeKeyId = maybeKeyId,
        maybePassphrase = env.get(EnvVarPgpPassphrase)
      )
      GpgArgs.MillGenerated(defaultGpgArgsForPassphrase(maybePassphrase))
    }
  }

  val EnvVarPgpPassphrase = "MILL_PGP_PASSPHRASE"
  val EnvVarPgpSecretBase64 = "MILL_PGP_SECRET_BASE64"

  enum GpgArgs {

    /**
     * When user provides the args himself, we can not log them because we do not know which ones are sensitive
     * information like a key passphrase.
     */
    case UserProvided(args: Seq[String])(using val file: sourcecode.File, val line: sourcecode.Line)

    /** When we generate the args ourselves we know which ones are secret. */
    case MillGenerated(args: Seq[PossiblySecret[String]])

    /** Turns this into the `gpg` arguments. */
    def asCommandArgs: Seq[String] = this match {
      case GpgArgs.UserProvided(args) => args
      case GpgArgs.MillGenerated(args) => args.iterator.map(Secret.unpack).toSeq
    }
  }
  object GpgArgs {

    /**
     * @param args a comma separated string, for example "--yes,--batch"
     */
    def fromUserProvided(args: String)(using sourcecode.File, sourcecode.Line): UserProvided =
      UserProvided(if (args.isBlank) Seq.empty else args.split(','))
  }

  case class GpgKey private (keyId: String, passphrase: Option[String]) {
    def gpgArgs: Seq[PossiblySecret[String]] =
      Seq("--local-user", keyId) ++ passphrase.iterator.flatMap(p => Seq("--passphrase", Secret(p)))
  }

  object GpgKey {

    /** Creates an instance if the passphrase is not empty. */
    def apply(keyId: String, passphrase: Option[String]): GpgKey =
      new GpgKey(keyId = keyId, passphrase = passphrase.filter(_.nonEmpty))

    /** Creates an instance if the passphrase is not empty. */
    def apply(keyId: String, passphrase: String): GpgKey =
      new GpgKey(keyId = keyId, passphrase = if (passphrase.isEmpty) None else Some(passphrase))

    /**
     * @param maybeKeyId      will be [[None]] if the PGP key was not provided in the environment.
     * @param maybePassphrase will be [[None]] if the PGP passphrase was not provided in the environment.
     */
    def createFromEnvVars(
        maybeKeyId: Option[String],
        maybePassphrase: Option[String]
    ): Option[Either[String, GpgKey]] =
      (maybeKeyId, maybePassphrase) match {
        case (None, None) => None
        case (Some(keyId), maybePassphrase) => Some(Right(apply(keyId = keyId, maybePassphrase)))
        // If passphrase is provided, key is required.
        case (None, Some(_)) =>
          Some(Left("A passphrase was provided, but key was not successfully imported."))
      }

    def createFromEnvVarsOrThrow(
        maybeKeyId: Option[String],
        maybePassphrase: Option[String]
    ): Option[GpgKey] =
      createFromEnvVars(maybeKeyId, maybePassphrase)
        .map(_.fold(err => throw new IllegalArgumentException(err), identity))
  }

  /**
   * Uri for publishing to the old / legacy Sonatype OSSRH.
   *
   * It's mostly dead but we still keep it around because
   * https://central.sonatype.org/publish/publish-portal-ossrh-staging-api still exists.
   *
   * @see https://central.sonatype.org/pages/ossrh-eol/
   */
  def sonatypeLegacyOssrhUri: String =
    "https://ossrh-staging-api.central.sonatype.com/service/local/"

  /** Uri for publishing SNAPSHOT artifacts to Sonatype Central. */
  def sonatypeCentralSnapshotUri: String =
    "https://central.sonatype.com/repository/maven-snapshots/"

  case class PublishData(meta: Artifact, payload: Seq[(PathRef, String)]) {

    /**
     * Maps the path reference to an actual path so that it can be used in publishAll signatures
     */
    private[mill] def withConcretePath: (Seq[(Path, String)], Artifact) =
      (payload.map { case (p, f) => (p.path, f) }, meta)
  }
  object PublishData {
    import mill.javalib.publish.JsonFormatters.artifactFormat
    implicit def jsonify: upickle.default.ReadWriter[PublishData] = upickle.default.macroRW
  }

  /**
   * Publish all given artifacts to Sonatype OSSRH (deprecated, use Sonatype Central publishing instead).
   *
   * Uses environment variables SONATYPE_USERNAME and SONATYPE_PASSWORD as
   * credentials.
   *
   * @param publishArtifacts what artifacts you want to publish. Defaults to `__.publishArtifacts`
   *                         which selects all `PublishModule`s in your build
   * @param sonatypeCreds Sonatype credentials in format username:password.
   *                      If specified, environment variables will be ignored.
   *                      <i>Note: consider using environment variables over this argument due
   *                      to security reasons.</i>
   * @param signed
   * @param gpgArgs       GPG arguments. Defaults to [[defaultGpgArgsForPassphrase]].
   *                      Specifying this will override/remove the defaults.
   *                      Add the default args to your args to keep them.
   * @param release Whether to release the artifacts after staging them
   * @param sonatypeUri Sonatype URI to use.
   * @param sonatypeSnapshotUri Sonatype snapshot URI to use.
   * @param readTimeout How long to wait before timing out network reads
   * @param connectTimeout How long to wait before timing out network connections
   * @param awaitTimeout How long to wait before timing out on failed uploads
   * @param stagingRelease
   * @return
   */
  def publishAll(
      publishArtifacts: Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__.publishArtifacts"),
      sonatypeCreds: String = "",
      signed: Boolean = true,
      gpgArgs: String = "",
      release: Boolean = true,
      sonatypeUri: String = sonatypeLegacyOssrhUri,
      sonatypeSnapshotUri: String = sonatypeCentralSnapshotUri,
      readTimeout: Int = 30 * 60 * 1000,
      connectTimeout: Int = 30 * 60 * 1000,
      awaitTimeout: Int = 30 * 60 * 1000,
      stagingRelease: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val withConcretePaths = Task.sequence(publishArtifacts.value)().map(_.withConcretePath)

    val gpgArgs0 =
      pgpImportSecretIfProvidedAndMakeGpgArgs(Task.env, GpgArgs.fromUserProvided(gpgArgs))

    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      checkSonatypeCreds(sonatypeCreds)(),
      signed,
      gpgArgs0,
      readTimeout,
      connectTimeout,
      Task.log,
      BuildCtx.workspaceRoot,
      Task.env,
      awaitTimeout,
      stagingRelease
    ).publishAll(
      release,
      withConcretePaths*
    )
  }

  private def getSonatypeCredsFromEnv: Task[(String, String)] = Task.Anon {
    (for {
      // Allow legacy environment variables as well
      username <- Task.env.get(USERNAME_ENV_VARIABLE_NAME).orElse(Task.env.get("SONATYPE_USERNAME"))
      password <- Task.env.get(PASSWORD_ENV_VARIABLE_NAME).orElse(Task.env.get("SONATYPE_PASSWORD"))
    } yield {
      (username, password)
    }).getOrElse(
      Task.fail(
        s"Consider using ${USERNAME_ENV_VARIABLE_NAME}/${PASSWORD_ENV_VARIABLE_NAME} environment variables or passing `sonatypeCreds` argument"
      )
    )
  }

  private[mill] def checkSonatypeCreds(sonatypeCreds: String): Task[String] =
    if (sonatypeCreds.isEmpty) {
      for {
        (username, password) <- getSonatypeCredsFromEnv
      } yield s"$username:$password"
    } else {
      Task.Anon {
        if (sonatypeCreds.split(":").length >= 2) {
          sonatypeCreds
        } else {
          Task.fail(
            "Sonatype credentials must be set in the following format - username:password. Incorrect format received."
          )
        }
      }
    }

  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]

}
