/*
 * Some parts of this code are taken from lefou/mill-jacoco. Copyright 2021-Present Tobias Roeser.
 */

package mill.kotlinlib.kover

import mill._
import mill.api.{Loose, PathRef}
import mill.api.Result.Success
import mill.define.{Discover, ExternalModule}
import mill.eval.Evaluator
import ReportType.{Html, Xml}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule, TestModule, Versions}
import mill.resolve.{Resolve, SelectMode}
import mill.scalalib.api.CompilationResult
import mill.util.Jvm
import os.Path

import java.util.Locale

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
 * import mill.kotlinlib.kover.KoverModule
 *
 * object foo extends KotlinModule with KoverModule {
 *   def kotlinVersion = "2.0.20"
 *
 *   object test extends KotlinTests with KoverTests
 * }
 * }}}
 *
 * In addition to the normal tasks available to your Kotlin module, Kover
 * Module introduce a few new tasks and changes the behavior of an existing one.
 *
 * - ./mill foo.test               # tests your project and collects metrics on code coverage
 * - ./mill foo.kover.htmlReport   # uses the metrics collected by a previous test run to generate a coverage report in html format
 * - ./mill foo.kover.xmlReport    # uses the metrics collected by a previous test run to generate a coverage report in xml format
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

  def koverBinaryReport: T[PathRef] = Task(persistent = true) {
    PathRef(koverDataDir().path / "kover-report.ic")
  }

  def koverDataDir: T[PathRef] = Task(persistent = true) { PathRef(T.dest) }

  object kover extends Module with KoverReportBaseModule {

    private def doReport(
        reportType: ReportType
    ): Task[PathRef] = Task.Anon {
      val reportPath = PathRef(T.dest).path / reportName
      Kover.runKoverCli(
        sourcePaths = outer.allSources().map(_.path),
        compiledPaths = Seq(outer.compile().classes.path),
        binaryReportsPaths = Seq(outer.koverBinaryReport().path),
        reportPath = reportPath,
        reportType = reportType,
        koverCliClasspath().map(_.path),
        T.dest
      )
    }

    def htmlReport(): Command[PathRef] = Task.Command { doReport(Html)() }
    def xmlReport(): Command[PathRef] = Task.Command { doReport(Xml)() }
  }

  trait KoverTests extends TestModule {

    private def koverAgentDep: T[Agg[Dep]] = Task {
      Agg(ivy"org.jetbrains.kotlinx:kover-jvm-agent:${koverVersion()}")
    }

    /** The Kover Agent is used at test-runtime. */
    private def koverAgentJar: T[PathRef] = Task {
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

/**
 * Allows the aggregation of coverage reports across multi-module projects.
 *
 * Once tests have been run across all modules, this collects reports from
 * all modules that extend [[KoverModule]].
 *
 * - ./mill __.test                                              # run tests for all modules
 * - ./mill mill.kotlinlib.kover.Kover/htmlReportAll     # generates report in html format for all modules
 * - ./mill mill.kotlinlib.kover.Kover/xmlReportAll      # generates report in xml format for all modules
 *
 * The aggregated report will be available at either `out/mill/kotlinlib/contrib/kover/Kover/htmlReportAll.dest/`
 * for html reports or `out/mill/kotlinlib/contrib/kover/Kover/xmlReportAll.dest/` for xml reports.
 */
object Kover extends ExternalModule with KoverReportBaseModule {

  lazy val millDiscover: Discover = Discover[this.type]

  def htmlReportAll(evaluator: Evaluator): Command[PathRef] = Task.Command {
    koverReportTask(
      evaluator = evaluator,
      reportType = ReportType.Html
    )()
  }

  def xmlReportAll(evaluator: Evaluator): Command[PathRef] = Task.Command {
    koverReportTask(
      evaluator = evaluator,
      reportType = ReportType.Xml
    )()
  }

  private def koverReportTask(
      evaluator: mill.eval.Evaluator,
      sources: String = "__:KotlinModule:^TestModule.allSources",
      compiled: String = "__:KotlinModule:^TestModule.compile",
      binaryReports: String = "__.koverBinaryReport",
      reportType: ReportType
  ): Task[PathRef] = {
    val sourcesTasks: Seq[Task[Seq[PathRef]]] = resolveTasks(sources, evaluator)
    val compiledTasks: Seq[Task[CompilationResult]] = resolveTasks(compiled, evaluator)
    val binaryReportTasks: Seq[Task[PathRef]] = resolveTasks(binaryReports, evaluator)

    Task.Anon {

      val sourcePaths: Seq[Path] =
        T.sequence(sourcesTasks)().flatten.map(_.path).filter(
          os.exists
        )
      val compiledPaths: Seq[Path] =
        T.sequence(compiledTasks)().map(_.classes.path).filter(
          os.exists
        )
      val binaryReportsPaths: Seq[Path] =
        T.sequence(binaryReportTasks)().map(_.path)
          .filter(path => {
            val exists = os.exists(path)
            if (!exists) {
              T.log.error(
                s"Kover binary report $path doesn't exist. Did you run tests for the module?."
              )
            }
            exists
          })

      val reportDir = PathRef(T.dest).path / reportName

      runKoverCli(
        sourcePaths,
        compiledPaths,
        binaryReportsPaths,
        reportDir,
        reportType,
        koverCliClasspath().map(_.path),
        T.dest
      )
    }
  }

  private[kover] def runKoverCli(
      sourcePaths: Seq[Path],
      compiledPaths: Seq[Path],
      binaryReportsPaths: Seq[Path],
      // will be treated as a dir in case of HTML, and as file in case of XML
      reportPath: Path,
      reportType: ReportType,
      classpath: Loose.Agg[Path],
      workingDir: os.Path
  )(implicit ctx: api.Ctx): PathRef = {
    val args = Seq.newBuilder[String]
    args += "report"
    args ++= binaryReportsPaths.map(_.toString())

    args ++= sourcePaths.flatMap(path => Seq("--src", path.toString()))
    args ++= compiledPaths.flatMap(path => Seq("--classfiles", path.toString()))
    val output = if (reportType == Xml) {
      s"${reportPath.toString()}.xml"
    } else reportPath.toString()
    args ++= Seq(s"--${reportType.toString.toLowerCase(Locale.US)}", output)
    Jvm.runSubprocess(
      mainClass = "kotlinx.kover.cli.MainKt",
      classPath = classpath,
      jvmArgs = Seq.empty[String],
      mainArgs = args.result(),
      workingDir = workingDir
    )
    PathRef(os.Path(output))
  }

  private def resolveTasks[T](tasks: String, evaluator: Evaluator): Seq[Task[T]] =
    if (tasks.trim().isEmpty) Seq.empty
    else Resolve.Tasks.resolve(evaluator.rootModule, Seq(tasks), SelectMode.Multi) match {
      case Left(err) => throw new Exception(err)
      case Right(tasks) => tasks.asInstanceOf[Seq[Task[T]]]
    }
}

sealed trait ReportType
object ReportType {
  final case object Html extends ReportType
  final case object Xml extends ReportType
}
