package mill
package contrib
package scoverage

import coursier.MavenRepository
import mill.api.Result
import mill.eval.PathRef
import mill.util.Ctx
import mill.scalalib.{DepSyntax, JavaModule, Lib, ScalaModule, TestModule, Dep}
import mill.moduledefs.Cacher


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
 *   def scalaVersion = "2.11.8"
 *   def scoverageVersion = "1.3.1"
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
 * The measurement data is available at `out/foo/scoverage/data/`,
 * the html report is saved in `out/foo/scoverage/htmlReport/`,
 * and the xml report is saved in `out/foo/scoverage/xmlReport/`.
 */
trait ScoverageModule extends ScalaModule { outer: ScalaModule =>
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
    val workerKey = "MILL_SCOVERAGE_REPORT_WORKER_" + scoverageVersion().replace(".", "_")
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-contrib-scoverage-worker-${outer.scoverageVersion()}",
      repositories,
      resolveFilter = _.toString.contains("mill-contrib-scoverage-worker")
    )
  }

  object scoverage extends ScalaModule {
    def selfDir = T { T.ctx().dest / os.up / os.up }
    def dataDir = T { selfDir() / "data" }

    def sources = outer.sources
    def resources = outer.resources
    def scalaVersion = outer.scalaVersion()
    def compileIvyDeps = outer.compileIvyDeps()
    def ivyDeps = outer.ivyDeps() ++ Agg(outer.scoverageRuntimeDep())
    def scalacPluginIvyDeps = outer.scalacPluginIvyDeps() ++ Agg(outer.scoveragePluginDep())
    def scalacOptions = outer.scalacOptions() ++
      Seq(s"-P:scoverage:dataDir:${dataDir()}")

    def htmlReport() = T.command {
      ScoverageReportWorkerApi
        .scoverageReportWorker()
        .bridge(toolsClasspath().map(_.path))
        .htmlReport(sources(), dataDir().toString, selfDir().toString)
    }
    def xmlReport() = T.command {
      ScoverageReportWorkerApi
        .scoverageReportWorker()
        .bridge(toolsClasspath().map(_.path))
        .xmlReport(sources(), dataDir().toString, selfDir().toString)
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
