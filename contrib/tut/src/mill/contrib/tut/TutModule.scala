package mill
package contrib.tut

import ammonite.ops.Path
import coursier.MavenRepository
import java.lang.reflect.Method
import java.net.URLClassLoader
import mill.scalalib._
import mill.util._
import scala.util.matching.Regex

trait TutModule extends ScalaModule {
  def tutSourceDirectory = T.sources { millSourcePath / 'tut }
  def tutTargetDirectory: T[Path] = T { T.ctx().dest }
  def tutClasspath: T[Agg[PathRef]] = T {
    // Same as compileClasspath but with tut added to ivyDeps from the start
    // This prevents duplicate copies of scala-library ending up on the classpath
    transitiveLocalClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    tutIvyDeps()
  }
  def tutScalacPluginIvyDeps: T[Agg[Dep]] = scalacPluginIvyDeps()
  def tutNameFilter: T[Regex] = T { """.*\.(md|markdown|txt|htm|html)""".r }
  def tutScalacOptions: T[Seq[String]] = scalacOptions()
  def tutVersion: T[String] = "0.6.7"

  def tutIvyDeps: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      repositories :+ MavenRepository(s"https://dl.bintray.com/tpolecat/maven"),
      Lib.depToDependency(_, scalaVersion()),
      compileIvyDeps() ++ transitiveIvyDeps() ++ Seq(
        ivy"org.tpolecat::tut-core:${tutVersion()}"
      )
    )
  }

  def tutPluginJars: T[Agg[PathRef]] = resolveDeps(tutScalacPluginIvyDeps)()

  def tut: T[Unit] = T {
    val in = tutSourceDirectory().head.path.toIO.getAbsolutePath
    val out = tutTargetDirectory().toIO.getAbsolutePath
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
