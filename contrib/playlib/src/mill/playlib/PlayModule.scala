package mill
package playlib

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util

import mill.PathRef
import mill.scalalib._
import scala.collection.JavaConverters._

import api.Versions

trait PlayApiModule extends Dependencies with Router with Server{
  trait PlayTests extends super.Tests{
    def testFrameworks = Seq("org.scalatest.tools.Framework")
    override def ivyDeps = T{
      playMinorVersion() match {
        case Versions.PLAY_2_6=>
          Agg(ivy"org.scalatestplus.play::scalatestplus-play::3.1.2")
        case Versions.PLAY_2_7=>
          Agg(ivy"org.scalatestplus.play::scalatestplus-play::4.0.1")
      }
    }
    override def sources = T.sources{ millSourcePath }
  }

  /**
    * project resources including configuration, webjars and static assets
    */
  def resources = T.sources {
    super.resources() :+ webJarResources() :+ staticAssets()
  }

  /**
    * Resource base path of packaged assets (path they will appear in in the jar)
    */
  def assetsPath = T{ "public" }

  /**
    *  Directories to include assets from
    */
  def assetSources = T.sources{ millSourcePath / 'public }

  /*
  Collected static assets for the project
   */
  def staticAssets = T {
    val toPath = os.Path(assetsPath(), T.ctx().dest)
    assetSources().foreach{ pathRef =>
      val fromPath = pathRef.path
      if (os.isDir(fromPath)) {
        os.walk(fromPath).filter(os.isFile(_)).foreach{ p =>
          os.copy(p, toPath / p.relativeTo(fromPath), createFolders = true)
        }
      }
    }
    PathRef(T.ctx().dest)
  }

  /**
    * webjar dependencies - created from transitive ivy deps
    */
  def webJarDeps = T{
    transitiveIvyDeps().filter(_.dep.module.organization == "org.webjars")
  }

  /**
    * jar files of web jars
    */
  def webJars = T{
    Lib.resolveDependencies(repositories, Lib.depToDependency(_, scalaVersion()), webJarDeps())
  }

  /**
    * webjar resources extracted from their source jars with version from path removed
    */
  def webJarResources = T {
    extractWebJars(webJars().toSeq, os.Path(assetsPath(), T.ctx().dest) / 'lib)
    PathRef(T.ctx().dest)
  }


  def start(args: String*) = T.command{ run(args:_*) }


  def extractWebJars(jars: Seq[PathRef], webJarBase: os.Path): Unit = {
    val prefix = "/META-INF/resources/webjars/"

    jars.foreach{ jarRef =>
      val uri = s"jar:file:${jarRef.path}"
      val env = Map.empty[String,String].asJava

      val zipFs = FileSystems.newFileSystem(URI.create(uri), env)
      try {
        for(root <- zipFs.getRootDirectories.asScala) {
          Files.walkFileTree(root, util.EnumSet.noneOf(classOf[FileVisitOption]), Int.MaxValue,
            new SimpleFileVisitor[Path] {
              override def visitFile(file: Path, attrs: BasicFileAttributes) = {
                if (file.startsWith(prefix)) {
                  val rel = os.RelPath(file.toString.substring(prefix.length))
                  val toFile = webJarBase / os.RelPath(rel.segments(0) +: rel.segments.drop(2), 0)
                  //println(s"$file -> $toFile")
                  os.makeDir.all(toFile / os.up)
                  Files.copy(file, toFile.toNIO)
                }
                FileVisitResult.CONTINUE
              }
            }
          )
        }
      }
      finally {
        zipFs.close()
      }
    }
  }

}
trait PlayModule extends PlayApiModule with Twirl
