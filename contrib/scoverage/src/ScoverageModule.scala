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
 * Consider this example Scala module.
 *  {{{
 *  import mill._, scalalib._
 *
 *  object foo extends ScalaModule {
 *    def scalaVersion = "2.12.4"
 *
 *    object test extends Tests {
 *      def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
 *      def testFrameworks = Seq("utest.runner.Framework")
 *    }
 *  }
 *  }}}
 *
 *  Converting it into ScoverageModule is simple:
 *  {{{
 *  import mill._, scalalib._, contrib.scoverage._
 *
 *  object baz extends ScoverageModule {
 *    def scalaVersion = "2.12.4"
 *    def scoverageVersion = "1.3.1"
 *
 *    object test extends ScoverageTests {
 *      def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
 *      def testFrameworks = Seq("utest.runner.Framework")
 *    }
 *  }
 *  }}}
 *
 *  You can compile the example like normally via
 *
 *  {{{
 *  mill baz.compile
 *  }}}
 *
 *  Running the tests is also familiar:
 *
 *  {{{
 *  mill baz.test
 *  }}}
 *
 *  However, after you've run the tests, you can also generate a coverage report:
 *
 *  {{{
 *  mill baz.scoverage.htmlReport
 *  }}}
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
    val workerKey = "MILL_SCOVERAGEREPORT_WORKER"
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
        .bridge(scoverageReportWorkerClasspath().map(_.path))
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
