package millbuild

import build_.package_ as build
import mill.{Task, PathRef, T}
import mill.scalalib.PublishModule
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl, PublishInfo}

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
   * Override to use the raw unprocessed jar for publishLocalTestRepo,
   * which is used for integration tests that expect SNAPSHOT versions.
   */
  override def defaultMainPublishInfos: Task[Seq[PublishInfo]] = Task.Anon {
    pomPackagingType match {
      case mill.scalalib.publish.PackagingType.Pom => Seq.empty
      case _ => Seq(PublishInfo.jar(jarRaw()))
    }
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
