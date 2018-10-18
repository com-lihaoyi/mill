package mill
package contrib.tut

import ammonite.ops._
import coursier.MavenRepository
import mill.scalalib._
import scala.util.matching.Regex

trait TutModule extends ScalaModule {
  def tutSourceDirectory = T.sources { millSourcePath / 'tut }
  def tutTargetDirectory: T[Path] = T { T.ctx().dest }
  def tutClasspath: T[Agg[PathRef]] = T {
    // Same as runClasspath but with tut added to ivyDeps from the start
    // This prevents duplicate copies of scala-library ending up on the classpath
    transitiveLocalClasspath() ++
    resources() ++
    localClasspath() ++
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

  def tutArgs: T[Seq[String]] = T {
    val in = tutSourceDirectory().head.path.toIO.getAbsolutePath
    val out = tutTargetDirectory().toIO.getAbsolutePath
    val re = tutNameFilter()
    val opts = tutScalacOptions()
    val pOpts = tutPluginJars().map(pathRef => "-Xplugin:" + pathRef.path.toIO.getAbsolutePath)
    List(in, out, re.pattern.toString) ++ opts ++ pOpts
  }

  def tut: T[CommandResult] = T {
    %%(
      'java,
      "-cp", tutClasspath().map(_.path.toIO.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "tut.TutMain",
      tutArgs()
    )(wd = millSourcePath)
  }
}
