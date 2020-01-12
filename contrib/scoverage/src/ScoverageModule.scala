package mill
package contrib
package scoverage

import coursier.MavenRepository
import mill.contrib.scoverage.api.ScoverageReportWorkerApi.ReportType
import mill.define.Persistent
import mill.eval.PathRef
import mill.scalalib.{DepSyntax, JavaModule, Lib, ScalaModule}


/** Adds targets to a [[mill.scalalib.ScalaModule]] to create test coverage reports.
 *
 * This module allows you to generate code coverage reports for Scala projects with
 * [[https://github.com/scoverage Scoverage]] via the
 * [[https://github.com/scoverage/scalac-scoverage-plugin scoverage compiler plugin]].
 *
 * To declare a module for which you want to generate coverage reports you can
 * Extends the `mill.contrib.scoverage.ScoverageModule` trait when defining your
 * Module. Additionally, you must define a submodule that extends the
 * `ScoverageTests` trait that belongs to your instance of `ScoverageModule`.
 *
 * {{{
 * // You have to replace VERSION
 * import $ivy.`com.lihaoyi::mill-contrib-buildinfo:VERSION`
 * import mill.contrib.scoverage.ScoverageModule
 *
 * Object foo extends ScoverageModule  {
 *   def scalaVersion = "2.12.9"
 *   def scoverageVersion = "1.4.0"
 *
 *   object test extends ScoverageTests {
 *     def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.5")
 *     def testFrameworks = Seq("org.scalatest.tools.Framework")
 *   }
 * }
 * }}}
 *
 * In addition to the normal tasks available to your Scala module, Scoverage
 * Modules introduce a few new tasks and changes the behavior of an existing one.
 *
 * - mill foo.scoverage.compile      # compiles your module with test instrumentation
 *                                 # (you don't have to run this manually, running the test task will force its invocation)
 *
 * - mill foo.test                   # tests your project and collects metrics on code coverage
 * - mill foo.scoverage.htmlReport   # uses the metrics collected by a previous test run to generate a coverage report in html format
 * - mill foo.scoverage.xmlReport    # uses the metrics collected by a previous test run to generate a coverage report in xml format
 *
 * The measurement data by default is available at `out/foo/scoverage/dataDir/dest/`,
 * the html report is saved in `out/foo/scoverage/htmlReport/dest/`,
 * and the xml report is saved in `out/foo/scoverage/xmlReport/dest/`.
 */
trait ScoverageModule extends ScalaModule { outer: ScalaModule =>
  /**
    * The Scoverage version to use.
    */
  def scoverageVersion: T[String]

  private def scoverageRuntimeDep = T {
    ivy"org.scoverage::scalac-scoverage-runtime:${outer.scoverageVersion()}"
  }
  private def scoveragePluginDep = T {
    ivy"org.scoverage::scalac-scoverage-plugin:${outer.scoverageVersion()}"
  }

  private def toolsClasspath = T {
    scoverageReportWorkerClasspath() ++ scoverageClasspath()
  }

  def scoverageClasspath = T {
    Lib.resolveDependencies(
      Seq(coursier.LocalRepositories.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      Lib.depToDependency(_, outer.scalaVersion()),
      Seq(scoveragePluginDep()),
      ctx = Some(implicitly[mill.util.Ctx.Log])
    )
  }

  def scoverageReportWorkerClasspath = T {
    val workerKey = "MILL_SCOVERAGE_REPORT_WORKER"
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-contrib-scoverage-worker",
      repositories,
      resolveFilter = _.toString.contains("mill-contrib-scoverage-worker")
    )
  }

  object scoverage extends ScalaModule {
    /**
      * The persistent data dir used to store scoverage coverage data.
      * Use to store coverage data at compile-time and by the various report targets.
      */
    def dataDir: Persistent[PathRef] = T.persistent {
      // via the persistent target, we ensure, the dest dir doesn't get cleared
      PathRef(T.dest)
    }

    override def generatedSources = outer.generatedSources()
    override def allSources = outer.allSources()
    override def moduleDeps = outer.moduleDeps
    override def sources = outer.sources
    override def resources = outer.resources
    override def scalaVersion = outer.scalaVersion()
    override def repositories = outer.repositories
    override def compileIvyDeps = outer.compileIvyDeps()
    override def ivyDeps = outer.ivyDeps() ++ Agg(outer.scoverageRuntimeDep())
    /** Add the scoverage scalac plugin. */
    override def scalacPluginIvyDeps = T{ outer.scalacPluginIvyDeps() ++ Agg(outer.scoveragePluginDep()) }
    /** Add the scoverage specific plugin settings (`dataDir`). */
    override def scalacOptions = T{ outer.scalacOptions() ++ Seq(s"-P:scoverage:dataDir:${dataDir().path.toIO.getPath()}") }

    def htmlReport() = T.command {
      ScoverageReportWorker
        .scoverageReportWorker()
        .bridge(toolsClasspath().map(_.path))
        .report(ReportType.Html, allSources().map(_.path), dataDir().path)
    }
    def xmlReport() = T.command {
      ScoverageReportWorker
        .scoverageReportWorker()
        .bridge(toolsClasspath().map(_.path))
        .report(ReportType.Xml, allSources().map(_.path), dataDir().path)
    }
  }

  trait ScoverageTests extends outer.Tests {
    override def upstreamAssemblyClasspath = T {
      super.upstreamAssemblyClasspath() ++
      resolveDeps(T.task{Agg(outer.scoverageRuntimeDep())})()
    }
    override def compileClasspath = T {
      super.compileClasspath() ++
      resolveDeps(T.task{Agg(outer.scoverageRuntimeDep())})()
    }
    override def runClasspath = T {
      super.runClasspath() ++
      resolveDeps(T.task{Agg(outer.scoverageRuntimeDep())})()
    }

    // Need the sources compiled with scoverage instrumentation to run.
    override def moduleDeps: Seq[JavaModule] = Seq(outer.scoverage)
  }
}
