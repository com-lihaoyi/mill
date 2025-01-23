package mill
package scalalib

import coursier.core.{Configuration, DependencyManagement}
import mill.define.{Command, ExternalModule, Task}
import mill.api.{JarManifest, PathRef, Result}
import mill.main.Tasks
import mill.scalalib.PublishModule.checkSonatypeCreds
import mill.scalalib.publish.SonatypeHelpers.{
  PASSWORD_ENV_VARIABLE_NAME,
  USERNAME_ENV_VARIABLE_NAME
}
import mill.scalalib.publish.{Artifact, SonatypePublisher}
import os.Path

/**
 * Configuration necessary for publishing a Scala module to Maven Central or similar
 */
trait PublishModule extends JavaModule { outer =>
  import mill.scalalib.publish._

  override def moduleDeps: Seq[PublishModule] = super.moduleDeps.map {
    case m: PublishModule => m
    case other =>
      throw new Exception(
        s"PublishModule moduleDeps need to be also PublishModules. $other is not a PublishModule"
      )
  }

  /**
   * The packaging type. See [[PackagingType]] for specially handled values.
   */
  def pomPackagingType: String = PackagingType.Jar

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

  def publishSelfDependency: T[Artifact] = Task {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def publishIvyDeps
      : Task[(Map[coursier.core.Module, String], DependencyManagement.Map) => Agg[Dependency]] =
    Task.Anon {
      (rootDepVersions: Map[coursier.core.Module, String], bomDepMgmt: DependencyManagement.Map) =>
        val bindDependency0 = bindDependency()
        val resolvePublishDependency0 = resolvePublishDependency.apply()

        // Ivy doesn't support BOM, so we try to add versions and exclusions from BOMs
        // to the dependencies themselves.
        def process(dep: mill.scalalib.Dep) = {
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

        val ivyPomDeps = allIvyDeps().map(process)

        val runIvyPomDeps = runIvyDeps().map(process)
          .filter(!ivyPomDeps.contains(_))

        val compileIvyPomDeps = compileIvyDeps().map(process)
          .filter(!ivyPomDeps.contains(_))

        val modulePomDeps = T.sequence(moduleDepsChecked.collect {
          case m: PublishModule => m.publishSelfDependency
        })()
        val compileModulePomDeps = T.sequence(compileModuleDepsChecked.collect {
          case m: PublishModule => m.publishSelfDependency
        })()
        val runModulePomDeps = T.sequence(runModuleDepsChecked.collect {
          case m: PublishModule => m.publishSelfDependency
        })()

        ivyPomDeps ++
          compileIvyPomDeps.map(_.copy(scope = Scope.Provided)) ++
          runIvyPomDeps.map(_.copy(scope = Scope.Runtime)) ++
          modulePomDeps.map(Dependency(_, Scope.Compile)) ++
          compileModulePomDeps.map(Dependency(_, Scope.Provided)) ++
          runModulePomDeps.map(Dependency(_, Scope.Runtime))
    }

  def publishXmlDeps: Task[Agg[Dependency]] = Task.Anon {
    val ivyPomDeps =
      allIvyDeps()
        .map(resolvePublishDependency.apply().apply(_))

    val runIvyPomDeps = runIvyDeps()
      .map(resolvePublishDependency.apply().apply(_))
      .filter(!ivyPomDeps.contains(_))

    val compileIvyPomDeps = compileIvyDeps()
      .map(resolvePublishDependency.apply().apply(_))
      .filter(!ivyPomDeps.contains(_))

    val modulePomDeps = T.sequence(moduleDepsChecked.collect {
      case m: PublishModule => m.publishSelfDependency
    })()
    val compileModulePomDeps = T.sequence(compileModuleDepsChecked.collect {
      case m: PublishModule => m.publishSelfDependency
    })()
    val runModulePomDeps = T.sequence(runModuleDepsChecked.collect {
      case m: PublishModule => m.publishSelfDependency
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
  def publishXmlBomDeps: Task[Agg[Dependency]] = Task.Anon {
    bomIvyDeps().map(resolvePublishDependency.apply().apply(_))
  }

  /**
   * Dependency management to specify in the POM
   */
  def publishXmlDepMgmt: Task[Agg[Dependency]] = Task.Anon {
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
    val pomPath = T.dest / s"${artifactId()}-${publishVersion()}.pom"
    os.write.over(pomPath, pom)
    PathRef(pomPath)
  }

  /**
   * Dependencies with version placeholder filled from BOMs, alongside with BOM data
   */
  @deprecated("Unused by Mill", "Mill after 0.12.5")
  def bomDetails: T[(Map[coursier.core.Module, String], coursier.core.DependencyManagement.Map)] =
    Task {
      val (processedDeps, depMgmt) = defaultResolver().processDeps(
        processedIvyDeps(),
        resolutionParams = resolutionParams(),
        boms = allBomDeps().toSeq.map(_.withConfig(Configuration.compile))
      )
      (processedDeps.map(_.moduleVersion).toMap, depMgmt)
    }

  def ivy: T[PathRef] = Task {
    val (results, bomDepMgmt) = defaultResolver().processDeps(
      Seq(
        BoundDep(
          coursierDependency.withConfiguration(Configuration.runtime),
          force = false
        )
      ),
      resolutionParams = resolutionParams()
    )
    val publishXmlDeps0 = {
      val rootDepVersions = results.map(_.moduleVersion).toMap
      publishIvyDeps.apply().apply(rootDepVersions, bomDepMgmt)
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
    val ivy = Ivy(artifactMetadata(), publishXmlDeps0, extraPublish(), overrides)
    val ivyPath = T.dest / "ivy.xml"
    os.write.over(ivyPath, ivy)
    PathRef(ivyPath)
  }

  def artifactMetadata: T[Artifact] = Task {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
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
   */
  def publishLocal(localIvyRepo: String = null): define.Command[Unit] = Task.Command {
    publishLocalTask(Task.Anon {
      Option(localIvyRepo).map(os.Path(_, T.workspace))
    })()
    Result.Success(())
  }

  /**
   * Publish artifacts the local ivy repository.
   */
  def publishLocalCached: T[Seq[PathRef]] = Task {
    publishLocalTask(Task.Anon(None))().map(p => PathRef(p).withRevalidateOnce)
  }

  private def publishLocalTask(localIvyRepo: Task[Option[os.Path]]): Task[Seq[Path]] = Task.Anon {
    val publisher = localIvyRepo() match {
      case None => LocalIvyPublisher
      case Some(path) => new LocalIvyPublisher(path)
    }
    publisher.publishLocal(
      jar = jar().path,
      sourcesJar = sourceJar().path,
      docJar = docJar().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifactMetadata(),
      extras = extraPublish()
    )
  }

  /**
   * Publish artifacts to a local Maven repository.
   * @param m2RepoPath The path to the local repository  as string (default: `$HOME/.m2repository`).
   *                   If not set, falls back to `maven.repo.local` system property or `~/.m2/repository`
   * @return [[PathRef]]s to published files.
   */
  def publishM2Local(m2RepoPath: String = null): Command[Seq[PathRef]] = m2RepoPath match {
    case null => Task.Command { publishM2LocalTask(Task.Anon { publishM2LocalRepoPath() })() }
    case p => Task.Command { publishM2LocalTask(Task.Anon { os.Path(p, T.workspace) })() }
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
      .getOrElse(os.Path(os.home / ".m2", T.workspace)) / "repository"
  }

  private def publishM2LocalTask(m2RepoPath: Task[os.Path]): Task[Seq[PathRef]] = Task.Anon {
    val path = m2RepoPath()
    new LocalM2Publisher(path)
      .publish(
        jar = jar().path,
        sourcesJar = sourceJar().path,
        docJar = docJar().path,
        pom = pom().path,
        artifact = artifactMetadata(),
        extras = extraPublish()
      ).map(PathRef(_).withRevalidateOnce)
  }

  def sonatypeUri: String = "https://oss.sonatype.org/service/local"

  def sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots"

  def publishArtifacts: T[PublishModule.PublishData] = {
    val baseNameTask: Task[String] = Task.Anon { s"${artifactId()}-${publishVersion()}" }
    val defaultPayloadTask: Task[Seq[(PathRef, String)]] = pomPackagingType match {
      case PackagingType.Pom => Task.Anon { Seq.empty[(PathRef, String)] }
      case PackagingType.Jar | _ => Task.Anon {
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
   * Publish all given artifacts to Sonatype.
   * Uses environment variables MILL_SONATYPE_USERNAME and MILL_SONATYPE_PASSWORD as
   * credentials.
   *
   * @param sonatypeCreds Sonatype credentials in format username:password.
   *                      If specified, environment variables will be ignored.
   *                      <i>Note: consider using environment variables over this argument due
   *                      to security reasons.</i>
   * @param gpgArgs       GPG arguments. Defaults to `--batch --yes -a -b`.
   *                      Specifying this will override/remove the defaults.
   *                      Add the default args to your args to keep them.
   */
  def publish(
      sonatypeCreds: String = "",
      signed: Boolean = true,
      // mainargs wasn't handling a default value properly,
      // so we instead use the empty Seq as default.
      // see https://github.com/com-lihaoyi/mill/pull/1678
      // TODO: In mill 0.11, we may want to change to a String argument
      // which we can split at `,` symbols, as we do in `PublishModule.publishAll`.
      gpgArgs: Seq[String] = Seq.empty,
      release: Boolean = true,
      readTimeout: Int = 30 * 60 * 1000,
      connectTimeout: Int = 30 * 60 * 1000,
      awaitTimeout: Int = 30 * 60 * 1000,
      stagingRelease: Boolean = true
  ): define.Command[Unit] = Task.Command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    PublishModule.pgpImportSecretIfProvided(T.env)
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      checkSonatypeCreds(sonatypeCreds)(),
      signed,
      if (gpgArgs.isEmpty) PublishModule.defaultGpgArgsForPassphrase(T.env.get("PGP_PASSPHRASE"))
      else gpgArgs,
      readTimeout,
      connectTimeout,
      T.log,
      T.workspace,
      T.env,
      awaitTimeout,
      stagingRelease
    ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo, release)
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

object PublishModule extends ExternalModule with TaskModule {
  def defaultCommandName(): String = "publishAll"
  val defaultGpgArgs: Seq[String] = defaultGpgArgsForPassphrase(None)
  def pgpImportSecretIfProvided(env: Map[String, String]): Unit = {
    for (secret <- env.get("MILL_PGP_SECRET_BASE64")) {
      os.call(
        ("gpg", "--import", "--no-tty", "--batch", "--yes"),
        stdin = java.util.Base64.getDecoder.decode(secret)
      )
    }
  }

  def defaultGpgArgsForPassphrase(passphrase: Option[String]): Seq[String] = {
    passphrase.map("--passphrase=" + _).toSeq ++
      Seq(
        "--no-tty",
        "--pinentry-mode",
        "loopback",
        "--batch",
        "--yes",
        "-a",
        "-b"
      )
  }

  case class PublishData(meta: Artifact, payload: Seq[(PathRef, String)]) {

    /**
     * Maps the path reference to an actual path so that it can be used in publishAll signatures
     */
    private[mill] def withConcretePath: (Seq[(Path, String)], Artifact) =
      (payload.map { case (p, f) => (p.path, f) }, meta)
  }
  object PublishData {
    import mill.scalalib.publish.artifactFormat
    implicit def jsonify: upickle.default.ReadWriter[PublishData] = upickle.default.macroRW
  }

  /**
   * Publish all given artifacts to Sonatype.
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
   * @param gpgArgs       GPG arguments. Defaults to `--passphrase=$MILL_PGP_PASSPHRASE,--no-tty,--pienty-mode,loopback,--batch,--yes,-a,-b`.
   *                      Specifying this will override/remove the defaults.
   *                      Add the default args to your args to keep them.
   * @param release Whether to release the artifacts after staging them
   * @param sonatypeUri Sonatype URI to use. Defaults to `oss.sonatype.org`, newer projects
   *                    may need to set it to https://s01.oss.sonatype.org/service/local
   * @param sonatypeSnapshotUri Sonatype snapshot URI to use. Defaults to `oss.sonatype.org`, newer projects
   *                            may need to set it to https://s01.oss.sonatype.org/content/repositories/snapshots
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
      sonatypeUri: String = "https://oss.sonatype.org/service/local",
      sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots",
      readTimeout: Int = 30 * 60 * 1000,
      connectTimeout: Int = 30 * 60 * 1000,
      awaitTimeout: Int = 30 * 60 * 1000,
      stagingRelease: Boolean = true
  ): Command[Unit] = Task.Command {
    val x: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map {
      case PublishModule.PublishData(a, s) => (s.map { case (p, f) => (p.path, f) }, a)
    }

    pgpImportSecretIfProvided(T.env)

    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      checkSonatypeCreds(sonatypeCreds)(),
      signed,
      if (gpgArgs.isEmpty) defaultGpgArgsForPassphrase(T.env.get("MILL_PGP_PASSPHRASE"))
      else gpgArgs.split(','),
      readTimeout,
      connectTimeout,
      T.log,
      T.workspace,
      T.env,
      awaitTimeout,
      stagingRelease
    ).publishAll(
      release,
      x: _*
    )
  }

  private def getSonatypeCredsFromEnv: Task[(String, String)] = Task.Anon {
    (for {
      // Allow legacy environment variables as well
      username <- T.env.get(USERNAME_ENV_VARIABLE_NAME).orElse(T.env.get("SONATYPE_USERNAME"))
      password <- T.env.get(PASSWORD_ENV_VARIABLE_NAME).orElse(T.env.get("SONATYPE_PASSWORD"))
    } yield {
      Result.Success((username, password))
    }).getOrElse(
      Result.Failure(
        s"Consider using ${USERNAME_ENV_VARIABLE_NAME}/${PASSWORD_ENV_VARIABLE_NAME} environment variables or passing `sonatypeCreds` argument"
      )
    )
  }

  private[scalalib] def checkSonatypeCreds(sonatypeCreds: String): Task[String] =
    if (sonatypeCreds.isEmpty) {
      for {
        (username, password) <- getSonatypeCredsFromEnv
      } yield s"$username:$password"
    } else {
      Task.Anon {
        if (sonatypeCreds.split(":").length >= 2) {
          Result.Success(sonatypeCreds)
        } else {
          Result.Failure(
            "Sonatype credentials must be set in the following format - username:password. Incorrect format received."
          )
        }
      }
    }

  lazy val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
}
