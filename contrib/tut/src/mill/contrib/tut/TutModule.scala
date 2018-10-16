package mill
package contrib.tut

import coursier.MavenRepository
import java.lang.reflect.Method
import java.net.URLClassLoader
import mill.scalalib._
import mill.util._
import scala.util.matching.Regex

trait TutModule extends ScalaModule {
  def tutSourceDirectory = T.sources { millSourcePath / 'tut }
  def tutTargetDirectory: T[PathRef] = T { PathRef(T.ctx().dest) }
  def tutClasspath: T[Agg[PathRef]] = tutJar() ++ compileClasspath()
  def tutPluginJars: T[Agg[PathRef]] = scalacPluginClasspath()
  def tutNameFilter: T[Regex] = T { """.*\.(md|markdown|txt|htm|html)""".r }
  def tutScalacOptions: T[Seq[String]] = scalacOptions()
  def tutVersion: T[String] = "0.6.7"

  def tutJar: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      repositories :+ MavenRepository(s"https://dl.bintray.com/tpolecat/maven"),
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"org.tpolecat::tut-core:${tutVersion()}")
    )
  }

  def tut: T[Unit] = T {
    val in = tutSourceDirectory().head.path.toIO.getAbsolutePath
    val out = tutTargetDirectory().path.toIO.getAbsolutePath
    val cp = tutClasspath()
    val opts = tutScalacOptions()
    val pOpts = tutPluginJars().map(pathRef => "-Xplugin:" + pathRef.path.toIO.getAbsolutePath)
    val re = tutNameFilter()
    val cl = new URLClassLoader(cp.map(_.path.toIO.toURI.toURL).toArray)
    val tutMainClass = cl.loadClass("tut.TutMain")
    val mainMethod = tutMainClass.getMethod("main", classOf[Array[java.lang.String]])
    val argsList = List(in, out, re.pattern.toString) ++ opts ++ pOpts
    mainMethod.invoke(null, argsList.toArray)
  }
}
