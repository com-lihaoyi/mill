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
   * The raw unprocessed jar, used for publishLocalTestRepo where we don't want
   * version processing (since tests use SNAPSHOT versions).
   */
  def jarRaw: T[PathRef] = Task { super.jar() }

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
   * Process a jar file, replacing `millVersion=SNAPSHOT` with the actual version
   * in any `.buildinfo.properties` files found within the jar.
   *
   * @param jarPath The source jar file
   * @param destDir The destination directory for the processed jar
   * @param millVersion The version to replace SNAPSHOT with
   * @return PathRef to the processed jar
   */
  def processJarVersion(jarPath: os.Path, destDir: os.Path, millVersion: String): PathRef = {
    val destJar = destDir / jarPath.last
    os.copy(jarPath, destJar, replaceExisting = true)

    val jarUri = new java.net.URI("jar", destJar.toNIO.toUri.toString, null)
    val env = new java.util.HashMap[String, String]()
    val fs = java.nio.file.FileSystems.newFileSystem(jarUri, env)
    try {
      val rootPath = fs.getPath("/")
      java.nio.file.Files.walk(rootPath).forEach { path =>
        if (path.toString.endsWith(".buildinfo.properties")) {
          val content = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
          if (content.contains("millVersion=SNAPSHOT")) {
            val updated = content.replace("millVersion=SNAPSHOT", s"millVersion=$millVersion")
            java.nio.file.Files.write(path, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          }
        }
      }
    } finally {
      fs.close()
    }

    PathRef(destJar)
  }

  /**
   * Process a self-executing jar (executable with shell script prefix), replacing
   * `millVersion=SNAPSHOT` with the actual version in any `.buildinfo.properties` files.
   *
   * Self-executing jars have a shell/batch script prepended to the jar. We need to:
   * 1. Find where the jar starts (PK magic bytes)
   * 2. Extract the prefix (shell script) and jar content
   * 3. Process the jar content
   * 4. Combine prefix + processed jar
   *
   * @param executablePath The source executable file
   * @param destDir The destination directory for the processed executable
   * @param millVersion The version to replace SNAPSHOT with
   * @return PathRef to the processed executable
   */
  def processExecutableVersion(executablePath: os.Path, destDir: os.Path, millVersion: String): PathRef = {
    val bytes = os.read.bytes(executablePath)

    // Find the start of the jar (PK signature: 0x50 0x4B 0x03 0x04)
    val jarStart = bytes.indices.find { i =>
      i + 3 < bytes.length &&
      bytes(i) == 0x50.toByte &&
      bytes(i + 1) == 0x4B.toByte &&
      bytes(i + 2) == 0x03.toByte &&
      bytes(i + 3) == 0x04.toByte
    }.getOrElse(
      throw new RuntimeException(s"Could not find jar signature in executable: $executablePath")
    )

    val prefix = bytes.slice(0, jarStart)
    val jarBytes = bytes.slice(jarStart, bytes.length)

    // Write jar to temp file for processing
    val tempJar = destDir / "temp.jar"
    os.write(tempJar, jarBytes)

    // Process the jar
    val jarUri = new java.net.URI("jar", tempJar.toNIO.toUri.toString, null)
    val env = new java.util.HashMap[String, String]()
    val fs = java.nio.file.FileSystems.newFileSystem(jarUri, env)
    try {
      val rootPath = fs.getPath("/")
      java.nio.file.Files.walk(rootPath).forEach { path =>
        if (path.toString.endsWith(".buildinfo.properties")) {
          val content = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
          if (content.contains("millVersion=SNAPSHOT")) {
            val updated = content.replace("millVersion=SNAPSHOT", s"millVersion=$millVersion")
            java.nio.file.Files.write(path, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          }
        }
      }
    } finally {
      fs.close()
    }

    // Combine prefix + processed jar
    val destExecutable = destDir / executablePath.last
    val processedJarBytes = os.read.bytes(tempJar)
    os.write(destExecutable, prefix ++ processedJarBytes)

    // Clean up temp jar
    os.remove(tempJar)

    // Preserve executable permissions
    if (!scala.util.Properties.isWin) {
      os.perms.set(destExecutable, os.perms(executablePath))
    }

    PathRef(destExecutable)
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
