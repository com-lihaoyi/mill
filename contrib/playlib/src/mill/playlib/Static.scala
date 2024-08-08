package mill.playlib

import java.net.URI
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util

import mill.scalalib.{Lib, ScalaModule}
import mill.{PathRef, T, task}

trait Static extends ScalaModule {

  /**
   * project resources including configuration, webjars and static assets
   */
  override def resources = task.sources {
    super.resources() :+ webJarResources() :+ staticAssets()
  }

  /**
   * Resource base path of packaged assets (path they will appear in in the jar)
   */
  def assetsPath = task { "public" }

  /**
   *  Directories to include assets from
   */
  def assetSources = task.sources { millSourcePath / assetsPath() }

  /*
  Collected static assets for the project
   */
  def staticAssets = task {
    val toPath = os.Path(assetsPath(), task.dest)
    assetSources().foreach { pathRef =>
      val fromPath = pathRef.path
      if (os.isDir(fromPath)) {
        os.walk(fromPath).filter(os.isFile(_)).foreach { p =>
          os.copy(p, toPath / p.relativeTo(fromPath), createFolders = true)
        }
      }
    }
    PathRef(task.dest)
  }

  /**
   * webjar dependencies - created from transitive ivy deps
   */
  def webJarDeps = task {
    transitiveIvyDeps().filter(_.dep.module.organization.value == "org.webjars")
  }

  /**
   * jar files of web jars
   */
  def webJars = task {
    Lib.resolveDependencies(
      repositoriesTask(),
      webJarDeps()
    )
  }

  /**
   * webjar resources extracted from their source jars with version from path removed
   */
  def webJarResources = task {
    extractWebJars(webJars().toSeq, os.Path(assetsPath(), task.dest) / "lib")
    PathRef(task.dest)
  }

  private def extractWebJars(jars: Seq[PathRef], webJarBase: os.Path): Unit = {
    import scala.jdk.CollectionConverters._
    val prefix = "/META-INF/resources/webjars/"

    jars.foreach { jarRef =>
      val uri = s"jar:file:${jarRef.path}"
      val env = Map.empty[String, String].asJava

      val zipFs = FileSystems.newFileSystem(URI.create(uri), env)
      try {
        for (root <- zipFs.getRootDirectories.asScala) {
          Files.walkFileTree(
            root,
            util.EnumSet.noneOf(classOf[FileVisitOption]),
            Int.MaxValue,
            new SimpleFileVisitor[Path] {
              override def visitFile(file: Path, attrs: BasicFileAttributes) = {
                if (file.startsWith(prefix)) {
                  val rel = os.RelPath(file.toString.substring(prefix.length))
                  val toFile = webJarBase / os.RelPath(rel.segments(0) +: rel.segments.drop(2), 0)
                  // println(s"$file -> $toFile")
                  os.makeDir.all(toFile / os.up)
                  Files.copy(file, toFile.toNIO)
                }
                FileVisitResult.CONTINUE
              }
            }
          )
        }
      } finally {
        zipFs.close()
      }
    }
  }
}
