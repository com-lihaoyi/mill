package millbuild

import build_.package_ as build
import mill.{Task, PathRef, T}
import mill.scalalib.PublishModule
import mill.scalalib.publish.{Artifact, Developer, License, LocalM2Publisher, Pom, PomSettings, VersionControl, PublishInfo}
import mill.api.TaskCtx

trait MillPublishJavaModule extends MillJavaModule with PublishModule {

  def artifactName = "mill-" + super.artifactName()
  def publishVersion = build.millVersion()
  def publishProperties = super.publishProperties() ++ Map(
    "info.releaseNotesURL" -> Settings.changelogUrl
  )
  def pomSettings = MillPublishJavaModule.commonPomSettings(artifactName())
  def javacOptions =
    super.javacOptions() ++ Seq("--release", "11", "-encoding", "UTF-8", "-deprecation")

  /**
   * Manifest without version info, used for jarRaw to avoid depending on publishVersion/millVersion.
   */
  def manifestRaw: T[mill.util.JarManifest] = Task {
    mill.util.Jvm.createManifest(finalMainClassOpt().toOption)
  }

  /**
   * The raw unprocessed jar, used for publishLocalTestRepo where we don't want
   * version processing (since tests use SNAPSHOT versions).
   * Uses manifestRaw to avoid depending on publishVersion/millVersion.
   */
  def jarRaw: T[PathRef] = Task {
    val jar = Task.dest / "out.jar"
    mill.util.Jvm.createJar(jar, localClasspath().map(_.path).filter(os.exists), manifestRaw())
    PathRef(jar)
  }

  /**
   * Processed jar with millVersion=SNAPSHOT replaced with the actual version.
   * Used for publishLocal and publishArtifacts.
   */
  override def jar: T[PathRef] = Task {
    val rawJar = jarRaw()
    MillPublishJavaModule.processJarVersion(rawJar.path, Task.dest, build.millVersion())
  }

  /**
   * Get dependencies for publishLocalTestRepo without depending on millVersion.
   * This task manually constructs dependencies using SNAPSHOT versions for Mill modules,
   * avoiding the call to artifactMetadata which would create a dependency on millVersion.
   */
  def publishLocalTestRepoDeps: Task[Seq[mill.scalalib.publish.Dependency]] = Task.Anon {
    import mill.scalalib.publish.{Dependency, Scope}

    // Get ivy dependencies (external jars)
    val ivyPomDeps = allMvnDeps().map(resolvePublishDependency.apply().apply(_))
    val runIvyPomDeps = runMvnDeps()
      .map(resolvePublishDependency.apply().apply(_))
      .filter(!ivyPomDeps.contains(_))
    val compileIvyPomDeps = compileMvnDeps()
      .map(resolvePublishDependency.apply().apply(_))
      .filter(!ivyPomDeps.contains(_))

    // Get module dependencies - construct artifacts with SNAPSHOT version directly
    // This avoids calling artifactMetadata which depends on publishVersion/millVersion
    // All module dependencies are Mill modules (external deps come from ivyDeps),
    // so we use SNAPSHOT for all of them
    // We resolve pomSettings and artifactId for each module using Task.traverse
    val modulePomDeps = Task.traverse(moduleDepsChecked.collect {
      case m: mill.scalalib.PublishModule => Task.Anon {
        Artifact(m.pomSettings().organization, m.artifactId(), "SNAPSHOT")
      }
    })(identity)()
    val compileModulePomDeps = Task.traverse(compileModuleDepsChecked.collect {
      case m: mill.scalalib.PublishModule => Task.Anon {
        Artifact(m.pomSettings().organization, m.artifactId(), "SNAPSHOT")
      }
    })(identity)()
    val runModulePomDeps = Task.traverse(runModuleDepsChecked.collect {
      case m: mill.scalalib.PublishModule => Task.Anon {
        Artifact(m.pomSettings().organization, m.artifactId(), "SNAPSHOT")
      }
    })(identity)()

    ivyPomDeps ++
      compileIvyPomDeps.map(_.copy(scope = Scope.Provided)) ++
      runIvyPomDeps.map(_.copy(scope = Scope.Runtime)) ++
      modulePomDeps.map(Dependency(_, Scope.Compile)) ++
      compileModulePomDeps.map(Dependency(_, Scope.Provided)) ++
      runModulePomDeps.map(Dependency(_, Scope.Runtime))
  }

  /**
   * Override publishLocalTestRepo to publish with version "SNAPSHOT" instead of
   * the actual millVersion. This is because the assembly jar has millVersion=SNAPSHOT
   * in its buildinfo, and the CoursierClient resolves dependencies with that version.
   *
   * Note: We explicitly use jarRaw() here instead of jar() to keep SNAPSHOT versions
   * in the buildinfo. This is different from publishLocal which uses jar() (processed).
   *
   * IMPORTANT: This task must NOT depend on millVersion to ensure integration tests
   * are not triggered by version changes.
   */
  override def publishLocalTestRepo: Task[PathRef] = Task {
    val snapshotVersion = "SNAPSHOT"
    val snapshotArtifact = Artifact(pomSettings().organization, artifactId(), snapshotVersion)

    // Generate POM with SNAPSHOT version and SNAPSHOT dependencies
    val snapshotPom = Pom(
      snapshotArtifact,
      publishLocalTestRepoDeps(),
      artifactId(),
      pomSettings(),
      publishProperties(),
      packagingType = pomPackagingType,
      parentProject = pomParentProject(),
      bomDependencies = Seq.empty,  // Skip BOM dependencies to avoid millVersion dependency
      dependencyManagement = Seq.empty  // Skip dep management to avoid millVersion dependency
    )
    val pomPath = Task.dest / s"${artifactId()}-$snapshotVersion.pom"
    os.write.over(pomPath, snapshotPom)

    val publisher = new LocalM2Publisher(Task.dest)
    // Use jarRaw() for the main jar to keep SNAPSHOT in buildinfo
    // Don't include sourceJar to avoid dependency on manifest/publishVersion/millVersion
    val rawJarInfo = pomPackagingType match {
      case mill.scalalib.publish.PackagingType.Pom => Seq.empty
      case _ => Seq(PublishInfo.jar(jarRaw()))
    }
    val publishInfos = rawJarInfo ++ extraPublish()
    publisher.publish(
      pom = pomPath,
      artifact = snapshotArtifact,
      publishInfos = publishInfos
    )(using
      new TaskCtx.Log {
        override def log: mill.api.daemon.Logger = mill.api.Logger.DummyLogger
      }
    )
    PathRef(Task.dest)
  }
}

object MillPublishJavaModule {

  /**
   * Process a jar file in-place, replacing SNAPSHOT with the actual version in:
   * - `.buildinfo.properties` files (millVersion=SNAPSHOT -> millVersion=<version>)
   * - `exampleList.txt` files (SNAPSHOT in URLs -> <version>)
   */
  private def processJarInPlace(jarPath: os.Path, millVersion: String): Unit = {
    scala.util.Using.resource(os.zip.open(jarPath)) { zipRoot =>
      os.walk(zipRoot).foreach { path =>
        if (path.last.endsWith(".buildinfo.properties")) {
          val content = os.read(path)
          if (content.contains("millVersion=SNAPSHOT")) {
            os.write.over(path, content.replace("millVersion=SNAPSHOT", s"millVersion=$millVersion"))
          }
        } else if (path.last == "exampleList.txt") {
          val content = os.read(path)
          if (content.contains("/SNAPSHOT/")) {
            os.write.over(path, content.replace("/SNAPSHOT/", s"/$millVersion/"))
          }
        }
      }
    }
  }

  /**
   * Process a jar or self-executing jar, replacing `millVersion=SNAPSHOT` with the actual version.
   *
   * For self-executing jars (with shell script prefix), the jar portion is extracted,
   * processed, and recombined since Java's ZipFileSystem doesn't handle the prefix.
   */
  def processJarVersion(jarPath: os.Path, destDir: os.Path, millVersion: String): PathRef = {
    val bytes = os.read.bytes(jarPath)

    // Find the start of the jar (PK signature: 0x50 0x4B 0x03 0x04)
    val jarStart = bytes.indices.find { i =>
      i + 3 < bytes.length &&
      bytes(i) == 0x50.toByte &&
      bytes(i + 1) == 0x4B.toByte &&
      bytes(i + 2) == 0x03.toByte &&
      bytes(i + 3) == 0x04.toByte
    }.getOrElse(
      throw new RuntimeException(s"Could not find jar signature: $jarPath")
    )

    val destJar = destDir / jarPath.last
    if (jarStart == 0) {
      // Regular jar - copy and process in place
      os.copy(jarPath, destJar, replaceExisting = true)
      processJarInPlace(destJar, millVersion)
    } else {
      // Self-executing jar - extract jar portion, process, recombine
      val prefix = bytes.slice(0, jarStart)
      val tempJar = destDir / "temp.jar"
      os.write(tempJar, bytes.slice(jarStart, bytes.length))
      processJarInPlace(tempJar, millVersion)
      os.write(destJar, prefix ++ os.read.bytes(tempJar))
      os.remove(tempJar)
    }

    PathRef(destJar)
  }

  def commonPomSettings(artifactName: String) = {
    PomSettings(
      description = artifactName,
      organization = Settings.pomOrg,
      url = Settings.projectUrl,
      licenses = Seq(License.MIT),
      versionControl = VersionControl.github(Settings.githubOrg, Settings.githubRepo),
      developers = Seq(
        Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"),
        Developer("lefou", "Tobias Roeser", "https://github.com/lefou")
      )
    )
  }
}
