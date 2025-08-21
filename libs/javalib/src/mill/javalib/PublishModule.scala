package mill
package javalib

import coursier.core.{Configuration, DependencyManagement}
import mill.api.daemon.Logger
import mill.api.{BuildCtx, DefaultTaskModule, ExternalModule, PathRef, Result, Task, TaskCtx}
import mill.util.{FileSetContents, JarManifest, Secret, Tasks}
import mill.javalib.publish.{Artifact, SonatypePublisher}
import os.Path
import mill.javalib.internal.PublishModule.{GpgArgs, checkSonatypeCreds}

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
    Ivy(artifactMetadata(), publishXmlDeps0, extras = extraPublish(), overrides, hasJar = hasJar)
  }

  def artifactMetadata: T[Artifact] = Task {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  @deprecated("Use `defaultPublishInfos` that takes parameters instead.", "1.0.1")
  def defaultPublishInfos: T[Seq[PublishInfo]] =
    Task { defaultPublishInfos(sources = true, docs = true)() }

  /** The [[PublishInfo]] of the [[defaultMainPublishInfos]] and optionally the [[sourcesJar]] and [[docJar]]. */
  final def defaultPublishInfos(sources: Boolean, docs: Boolean): Task[Seq[PublishInfo]] = {
    val sourcesJarOpt =
      if (sources) Task.Anon(Some(PublishInfo.sourcesJar(sourceJar())))
      else Task.Anon(None)

    val docJarOpt =
      if (docs) Task.Anon(Some(PublishInfo.docJar(docJar())))
      else Task.Anon(None)

    Task.Anon(defaultMainPublishInfos() ++ sourcesJarOpt() ++ docJarOpt())
  }

  /** The [[PublishInfo]] of the main artifact, which can have multiple files. */
  def defaultMainPublishInfos: Task[Seq[PublishInfo]] = {
    pomPackagingType match {
      case PackagingType.Pom => Task.Anon(Seq.empty)
      case _ => Task.Anon(Seq(PublishInfo.jar(jar())))
    }
  }

  /**
   * Extra artifacts to publish.
   */
  def extraPublish: T[Seq[PublishInfo]] = Task { Seq.empty[PublishInfo] }

  /** [[defaultPublishInfos]] + [[extraPublish]] */
  final def allPublishInfos(sources: Boolean, docs: Boolean): Task[Seq[PublishInfo]] = Task.Anon {
    defaultPublishInfos(sources = sources, docs = docs)() ++ extraPublish()
  }

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
  ): Task[Seq[Path]] = {
    if (transitive) {
      val publishTransitiveModuleDeps = (transitiveModuleDeps ++ transitiveRunModuleDeps).collect {
        case p: PublishModule => p
      }
      Task.traverse(publishTransitiveModuleDeps.distinct) { publishMod =>
        publishMod.publishLocalTask(localIvyRepo, sources, doc, transitive = false)
      }.map(_.flatten)
    } else {
      Task.Anon {
        val contents = publishLocalContentsTask(sources = sources, doc = doc)()
        val publisher = localIvyRepo() match {
          case None => LocalIvyPublisher
          case Some(path) => new LocalIvyPublisher(path)
        }
        publisher.publishLocal(contents.artifact, contents.contents)
      }
    }
  }

  /** Produce the contents for the ivy publishing. */
  private def publishLocalContentsTask(
      sources: Boolean,
      doc: Boolean
  ): Task[(artifact: Artifact, contents: Map[os.SubPath, FileSetContents.Writable])] = {
    Task.Anon {
      val publishInfos = allPublishInfos(sources = sources, docs = doc)()
      val artifact = artifactMetadata()
      val contents = LocalIvyPublisher.createFileSetContents(
        artifact = artifact,
        pom = pom().path,
        ivy = ivy().path,
        publishInfos = publishInfos
      )
      (artifact, contents)
    }
  }

  /**
   * Publish artifacts to a local Maven repository.
   *
   * @param m2RepoPath The path to the local repository  as string (default: `$HOME/.m2/repository`).
   *                   If not set, falls back to `maven.repo.local` system property or `~/.m2/repository`
   * @return [[PathRef]]s to published files.
   */
  def publishM2Local(m2RepoPath: String = null): Task.Command[Seq[PathRef]] = m2RepoPath match {
    case null => Task.Command {
        publishM2LocalTask(
          Task.Anon { publishM2LocalRepoPath() },
          sources = true,
          docs = true
        )()
      }
    case p =>
      Task.Command {
        publishM2LocalTask(
          Task.Anon { os.Path(p, BuildCtx.workspaceRoot) },
          sources = true,
          docs = true
        )()
      }
  }

  /**
   * Publish artifacts to the local Maven repository.
   * @return [[PathRef]]s to published files.
   */
  def publishM2LocalCached: T[Seq[PathRef]] = Task {
    publishM2LocalTask(publishM2LocalRepoPath, sources = true, docs = true)()
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

  private def publishM2LocalTask(
      m2RepoPath: Task[os.Path],
      sources: Boolean,
      docs: Boolean
  ): Task[Seq[PathRef]] = Task.Anon {
    val path = m2RepoPath()
    val contents = publishM2LocalContentsTask(sources = sources, docs = docs)()

    new LocalM2Publisher(path)
      .publish(contents.artifact, contents.contents)
      .map(PathRef(_).withRevalidateOnce)
  }

  /** Produce the contents for the maven publishing. */
  private def publishM2LocalContentsTask(sources: Boolean, docs: Boolean)
      : Task[(artifact: Artifact, contents: Map[os.SubPath, os.Path])] = Task.Anon {
    val publishInfos = allPublishInfos(sources = sources, docs = docs)()
    val artifact = artifactMetadata()
    val contents =
      LocalM2Publisher.createFileSetContents(pom = pom().path, artifact = artifact, publishInfos)

    (artifact, contents)
  }

  /** @see [[PublishModule.sonatypeLegacyOssrhUri]] */
  def sonatypeLegacyOssrhUri: String = PublishModule.sonatypeLegacyOssrhUri

  /** @see [[PublishModule.sonatypeCentralSnapshotUri]] */
  def sonatypeCentralSnapshotUri: String = PublishModule.sonatypeCentralSnapshotUri

  @deprecated(
    "Do not override this task, override `artifactMetadata`, `publishArtifactsDefaultPayload` or `extraPublish` instead.",
    "1.0.1"
  )
  def publishArtifacts: T[PublishModule.PublishData] = Task {
    PublishModule.PublishData(artifactMetadata(), publishArtifactsPayload()())
  }

  /** [[publishArtifactsDefaultPayload]] with [[extraPublish]]. */
  final def publishArtifactsPayload(
      sources: Boolean = true,
      docs: Boolean = true
  ): Task[Map[os.SubPath, PathRef]] = Task {
    val defaultPayload = publishArtifactsDefaultPayload(sources = sources, docs = docs)()
    val baseName = publishArtifactsBaseName()
    val extraPayload = extraPublishPayload()(baseName)
    defaultPayload ++ extraPayload
  }

  private def extraPublishPayload = Task.Anon { (baseName: String) =>
    extraPublish().iterator.map(p =>
      os.SubPath(s"$baseName${p.classifierPart}.${p.ext}") -> p.file
    ).toMap
  }

  /** The base name for the published artifacts. */
  def publishArtifactsBaseName: T[String] =
    Task { s"${artifactId()}-${publishVersion()}" }

  /**
   * The default payload for the published artifacts.
   *
   * @param sources whether to include sources JAR when [[pomPackagingType]] is [[PackagingType.Jar]]
   * @param docs whether to include javadoc JAR when [[pomPackagingType]] is [[PackagingType.Jar]]
   */
  def publishArtifactsDefaultPayload(
      sources: Boolean = true,
      docs: Boolean = true
  ): Task[Map[os.SubPath, PathRef]] = {
    pomPackagingType match {
      case PackagingType.Pom => Task.Anon {
          val baseName = publishArtifactsBaseName()
          Map(
            os.SubPath(s"$baseName.pom") -> pom()
          )
        }

      case _ => Task.Anon {
          val baseName = publishArtifactsBaseName()
          val baseContent = Map(
            os.SubPath(s"$baseName.pom") -> pom(),
            os.SubPath(s"$baseName.jar") -> jar()
          )
          val sourcesOpt =
            if (sources) Map(os.SubPath(s"$baseName-sources.jar") -> sourceJar()) else Map.empty
          val docsOpt =
            if (docs) Map(os.SubPath(s"$baseName-javadoc.jar") -> docJar()) else Map.empty
          baseContent ++ sourcesOpt ++ docsOpt
        }
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
    val gpgArgs0 = internal.PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
      Task.env,
      GpgArgs.fromUserProvided(gpgArgs)
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

  /**
   * Creates a Maven repository directory with main and source artifacts for this module
   *
   * This results in a directory which contains things like
   * `my/organization/my-module/VERSION/my-module-VERSION.{pom,jar}`.
   * Such directories can later be used as Maven repositories during
   * artifact resolution, or be merged together and then used as repository,
   * for example.
   */
  def publishLocalTestRepo: Task[PathRef] = Task {
    val publisher = new LocalM2Publisher(Task.dest)
    val publishInfos = defaultPublishInfos(sources = false, docs = false)() ++
      Seq(PublishInfo.sourcesJar(sourceJar())) ++
      extraPublish()
    publisher.publish(
      pom = pom().path,
      artifact = artifactMetadata(),
      publishInfos = publishInfos
    )(using
      new TaskCtx.Log {
        // Silence logging for publishLocalTestRepo since it's very spammy during
        // development, and since it's all local we can just see the files on disk
        override def log: Logger = mill.api.Logger.DummyLogger
      }
    )
    PathRef(Task.dest)
  }
}

object PublishModule extends ExternalModule with DefaultTaskModule {
  def defaultTask(): String = "publishAll"

  val defaultGpgArgs: Seq[String] = internal.PublishModule.defaultGpgArgs

  @deprecated("This API should have been internal and is not guaranteed to stay.", "1.0.1")
  def pgpImportSecretIfProvided(env: Map[String, String]): Unit =
    internal.PublishModule.pgpImportSecretIfProvidedOrThrow(env)

  @deprecated("This API should have been internal and is not guaranteed to stay.", "1.0.1")
  def defaultGpgArgsForPassphrase(passphrase: Option[String]): Seq[String] =
    internal.PublishModule.defaultGpgArgsForPassphrase(passphrase).map(Secret.unpack)

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

  case class PublishData(
      meta: Artifact,
      // Unfortunately we can't change the signature of this to `Map[os.SubPath, PathRef]` because
      // we need to keep the binary compatibility and case classes are notoriously difficult to change
      // while preserving binary compatibility.
      //
      // So instead we convert back and forth.
      payload: Seq[(PathRef, String)]
  ) {
    def payloadAsMap: Map[os.SubPath, PathRef] = PublishData.seqToMap(payload)

    /** Maps the path reference to an actual path. */
    private[mill] def withConcretePath: (Map[os.SubPath, os.Path], Artifact) =
      (PublishData.withConcretePath(payloadAsMap), meta)
  }
  object PublishData {
    implicit def jsonify: upickle.default.ReadWriter[PublishData] = {
      import mill.javalib.publish.JsonFormatters.artifactFormat
      upickle.default.macroRW
    }

    def apply(meta: Artifact, payload: Map[os.SubPath, PathRef]): PublishData =
      apply(meta, mapToSeq(payload))

    private def seqToMap(payload: Seq[(PathRef, String)]): Map[os.SubPath, PathRef] =
      payload.iterator.map { case (pathRef, name) => os.SubPath(name) -> pathRef }.toMap

    private def mapToSeq(payload: Map[os.SubPath, PathRef]): Seq[(PathRef, String)] =
      payload.iterator.map { case (name, pathRef) => pathRef -> name.toString }.toSeq

    /** Maps the path reference to an actual path. */
    private[mill] def withConcretePath(payload: Map[os.SubPath, PathRef])
        : Map[os.SubPath, os.Path] =
      payload.view.mapValues(_.path).toMap
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
      sonatypeUri: String = PublishModule.sonatypeLegacyOssrhUri,
      sonatypeSnapshotUri: String = PublishModule.sonatypeCentralSnapshotUri,
      readTimeout: Int = 30 * 60 * 1000,
      connectTimeout: Int = 30 * 60 * 1000,
      awaitTimeout: Int = 30 * 60 * 1000,
      stagingRelease: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val withConcretePaths = Task.sequence(publishArtifacts.value)().map(_.withConcretePath)

    val gpgArgs0 =
      internal.PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
        Task.env,
        GpgArgs.fromUserProvided(gpgArgs)
      )

    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      internal.PublishModule.checkSonatypeCreds(sonatypeCreds)(),
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

  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]

}
