/*
 * Some parts of this code are taken from lefou/mill-jacoco. Copyright 2021-Present Tobias Roeser.
 */

package mill
package kotlinlib.contrib.kover

import mill.api.PathRef
import mill.api.Result.Success
import mill.kotlinlib.contrib.kover.ReportType.{Html, Xml}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule, TestModule, Versions}

/**
 * Adds targets to a [[mill.kotlinlib.KotlinModule]] to create test coverage reports.
 *
 * This module allows you to generate code coverage reports for Kotlin projects with
 * [[https://github.com/Kotlin/kotlinx-kover Kover]].
 *
 * To declare a module for which you want to generate coverage reports you can
 * mix the [[KoverModule]] trait when defining your module. Additionally, you must define a submodule that extends the
 * [[KoverTests]] trait that belongs to your instance of [[KoverModule]].
 *
 * {{{
 * import mill.kotlinlib.KotlinModule
 * import mill.kotlinlib.contrib.kover.KoverModule
 *
 * object foo extends KotlinModule with KoverModule {
 *   def kotlinVersion = "2.0.20"
 *
 *   object test extends KotlinModuleTests with KoverTests
 * }
 * }}}
 *
 * In addition to the normal tasks available to your Kotlin module, Kover
 * Module introduce a few new tasks and changes the behavior of an existing one.
 *
 * - mill foo.test               # tests your project and collects metrics on code coverage
 * - mill foo.kover.htmlReport   # uses the metrics collected by a previous test run to generate a coverage report in html format
 * - mill foo.kover.xmlReport    # uses the metrics collected by a previous test run to generate a coverage report in xml format
 *
 * The measurement data by default is available at `out/foo/kover/koverDataDir.dest/`,
 * the html report is saved in `out/foo/kover/htmlReport.dest/`,
 * and the xml report is saved in `out/foo/kover/xmlReport.dest/`.
 */
trait KoverModule extends KotlinModule { outer =>

  /**
   * Reads the Kover version from system environment variable `KOVER_VERSION` or defaults to a hardcoded version.
   */
  def koverVersion: T[String] = Task.Input {
    Success[String](T.env.getOrElse("KOVER_VERSION", Versions.koverVersion))
  }

  def koverBinaryReport: T[PathRef] =
    Task.Persistent { PathRef(koverDataDir().path / "kover-report.ic") }

  def koverDataDir: T[PathRef] = Task.Persistent { PathRef(T.dest) }

  object kover extends Module with KoverReportBaseModule {

    private def doReport(
        reportType: ReportType
    ): Task[PathRef] = Task.Anon {
      val reportPath = PathRef(T.dest).path / reportName
      KoverReport.runKoverCli(
        sourcePaths = outer.allSources().map(_.path),
        compiledPaths = Seq(outer.compile().classes.path),
        binaryReportsPaths = Seq(outer.koverBinaryReport().path),
        reportPath = reportPath,
        reportType = reportType,
        koverCliClasspath().map(_.path),
        T.dest
      )
      PathRef(reportPath)
    }

    def htmlReport(): Command[PathRef] = Task.Command { doReport(Html)() }
    def xmlReport(): Command[PathRef] = Task.Command { doReport(Xml)() }
  }

  trait KoverTests extends TestModule {

    private def koverAgentDep: T[Agg[Dep]] = T {
      Agg(ivy"org.jetbrains.kotlinx:kover-jvm-agent:${koverVersion()}")
    }

    /** The Kover Agent is used at test-runtime. */
    private def koverAgentJar: T[PathRef] = T {
      val jars = defaultResolver().resolveDeps(koverAgentDep())
      jars.iterator.next()
    }

    /**
     * Add Kover specific javaagent options.
     */
    override def forkArgs: T[Seq[String]] = Task {
      val argsFile = koverDataDir().path / "kover-agent.args"
      val content = s"report.file=${koverBinaryReport().path}"
      os.write.over(argsFile, content)

      super.forkArgs() ++
        Seq(
          s"-javaagent:${koverAgentJar().path}=file:$argsFile"
        )
    }
  }
}
