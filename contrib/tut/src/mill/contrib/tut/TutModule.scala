package mill
package contrib.tut

import coursier.MavenRepository
import mill.scalalib._
import scala.util.matching.Regex

/**
  * Tut is a documentation tool which compiles and evaluates Scala code in documentation files and provides various options for configuring how the results will be displayed in the compiled documentation.
  *
  * Extending this trait declares a Scala module which compiles markdown, HTML and `.txt` files in the `tut` folder of the module with Tut.
  *
  * By default the resulting documents are simply placed in the Mill build output folder but they can be placed elsewhere by overriding the [[mill.contrib.tut.TutModule#tutTargetDirectory]] task.
  *
  * For example:
  *
  * {{{
  * // build.sc
  * import mill._, scalalib._, contrib.tut.__
  *
  * object example extends TutModule {
  *   def scalaVersion = "2.12.6"
  *   def tutVersion = "0.6.7"
  * }
  * }}}
  *
  * This defines a project with the following layout:
  *
  * {{{
  * build.sc
  * example/
  *     src/
  *     tut/
  *     resources/
  * }}}
  *
  * In order to compile documentation we can execute the `tut` task in the module:
  *
  * {{{
  * sh> mill example.tut
  * }}}
  */
trait TutModule extends ScalaModule {
  /**
    * This task determines where documentation files must be placed in order to be compiled with Tut. By default this is the `tut` folder at the root of the module.
    */
  def tutSourceDirectory = T.sources { millSourcePath / 'tut }

  /**
    * A task which determines where the compiled documentation files will be placed. By default this is simply the Mill build's output folder for this task,
    * but this can be reconfigured so that documentation goes to the root of the module (e.g. `millSourcePath`) or to a dedicated folder (e.g. `millSourcePath / 'docs`)
    */
  def tutTargetDirectory: T[os.Path] = T { T.ctx().dest }

  /**
    * A task which determines what classpath is used when compiling documentation. By default this is configured to use the same inputs as the [[mill.contrib.tut.TutModule#runClasspath]],
    * except for using [[mill.contrib.tut.TutModule#tutIvyDeps]] rather than the module's [[mill.contrib.tut.TutModule#runIvyDeps]].
    */
  def tutClasspath: T[Agg[PathRef]] = T {
    // Same as runClasspath but with tut added to ivyDeps from the start
    // This prevents duplicate, differently versioned copies of scala-library ending up on the classpath which can happen when resolving separately
    transitiveLocalClasspath() ++
    resources() ++
    localClasspath() ++
    unmanagedClasspath() ++
    tutIvyDeps()
  }

  /**
    * A task which determines the scalac plugins which will be used when compiling code examples with Tut. The default is to use the [[mill.contrib.tut.TutModule#scalacPluginIvyDeps]] for the module.
    */
  def tutScalacPluginIvyDeps: T[Agg[Dep]] = scalacPluginIvyDeps()

  /**
    * A [[scala.util.matching.Regex]] task which will be used to determine which files should be compiled with tut. The default pattern is as follows: `.*\.(md|markdown|txt|htm|html)`.
    */
  def tutNameFilter: T[Regex] = T { """.*\.(md|markdown|txt|htm|html)""".r }

  /**
    * The scalac options which will be used when compiling code examples with Tut. The default is to use the [[mill.contrib.tut.TutModule#scalacOptions]] for the module,
    * but filtering out options which are problematic in the REPL, e.g. `-Xfatal-warnings`, `-Ywarn-unused-imports`.
    */
  def tutScalacOptions: T[Seq[String]] =
    scalacOptions().filterNot(Set(
      "-Ywarn-unused:imports",
      "-Ywarn-unused-import",
      "-Ywarn-dead-code",
      "-Xfatal-warnings"
    ))

  /**
    * The version of Tut to use.
    */
  def tutVersion: T[String]

  /**
    * A task which determines how to fetch the Tut jar file and all of the dependencies required to compile documentation for the module and returns the resulting files.
    */
  def tutIvyDeps: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      repositories :+ MavenRepository(s"https://dl.bintray.com/tpolecat/maven"),
      Lib.depToDependency(_, scalaVersion()),
      compileIvyDeps() ++ transitiveIvyDeps() ++ Seq(
        ivy"org.tpolecat::tut-core:${tutVersion()}"
      )
    )
  }

  /**
    * A task which performs the dependency resolution for the scalac plugins to be used with Tut.
    */
  def tutPluginJars: T[Agg[PathRef]] = resolveDeps(tutScalacPluginIvyDeps)()

  /**
    * Run Tut using the configuration specified in this module. The working directory used is the [[mill.contrib.tut.TutModule#millSourcePath]].
    */
  def tut: T[os.CommandResult] = T {
    val in = tutSourceDirectory().head.path.toIO.getAbsolutePath
    val out = tutTargetDirectory().toIO.getAbsolutePath
    val re = tutNameFilter()
    val opts = tutScalacOptions()
    val pOpts = tutPluginJars().map(pathRef => "-Xplugin:" + pathRef.path.toIO.getAbsolutePath)
    val tutArgs = List(in, out, re.pattern.toString) ++ opts ++ pOpts
    os.proc(
      'java,
      "-cp", tutClasspath().map(_.path.toIO.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "tut.TutMain",
      tutArgs
    ).call(millSourcePath)
  }
}
