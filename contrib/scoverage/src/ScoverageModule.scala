package mill
package contrib
package scoverage

import coursier.{Cache, MavenRepository}
import mill.api.{Loose, Result}
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
 * <pre>
 * Mill foo.scoverage.compile      # compiles your module with test instrumentation
 *                                 # (you don't have to run this manually, running the test task will force its invocation)
 *
 * Mill foo.test                   # tests your project and collects metrics on code coverage
 * Mill foo.scoverage.htmlReport   # uses the metrics collected by a previous test run to generate a coverage report in html format
 * </pre>
 *
 * The measurement data is available at `out/foo/scoverage/data/`,
 * And the html report is saved in `out/foo/scoverage/htmlReport/`.
 */
trait ScoverageModule extends ScalaModule { outer: ScalaModule =>
  def scoverageVersion: T[String]
  private def scoverageRuntimeDep = T {
    ivy"org.scoverage::scalac-scoverage-runtime:${outer.scoverageVersion()}"
  }
  private def scoveragePluginDep = T {
    ivy"org.scoverage::scalac-scoverage-plugin:${outer.scoverageVersion()}"
  }

  def scoverageReportWorkerClasspath = T {
    val workerKey = "MILL_SCOVERAGE_REPORT_WORKER_" + scoverageVersion().replace(".", "_")
    val workerPath = sys.props(workerKey)
    if (workerPath != null)
      Result.Success(Agg(workerPath.split(',').map(p => PathRef(os.Path(p), quick = true)): _*))
    else
      Lib.resolveDependencies(
        Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
        Lib.depToDependency(_, outer.scalaVersion()),
        Seq(scoveragePluginDep()),
        ctx = Some(implicitly[mill.util.Ctx.Log])
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
        .bridge(sources(), scoverageReportWorkerClasspath().map(_.path))
        .htmlReport(dataDir().toString, selfDir().toString)
    }
  }

  trait ScoverageTests extends outer.Tests {
    // This ensures that the runtime is added to the ivy deps
    // specified by the user. Unfortunately it also clutters
    // any calls to agg inside of ScoverageTests with this dependency,
    // seems like a reasonable trade-off since it is only for test sources.
    def Agg(items: Dep*) = T { Loose.Agg(outer.scoverageRuntimeDep()) ++ Loose.Agg(items: _*) }

    // Need the sources compiled with scoverage instrumentation to run.
    override def moduleDeps: Seq[JavaModule] = Seq(outer.scoverage)
  }

  def test: ScoverageTests
}
