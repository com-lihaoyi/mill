package mill.contrib.junit
import mill.api.Logger
import mill.define.Segment.Label
import mill.define.{Command, Module, NamedTask, Segment, Segments, Target}
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.resolve.{Resolve, SelectMode}
import mill.scalalib.api.CompilationResult
import mill.testrunner.TestResult
import mill.{PathRef, T}

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import scala.xml.XML

/**
 * Allows the generation of JUnit XML legacy reports across multi-module projects.
 *
 * Once tests have been run across all modules, this collects reports from
 * all modules that contains test data. Simply
 * define a module that extends [[mill.contrib.junit.JUnitReport]] and
 * call the "report all" function.
 *
 * For example, define the following `junit` module to generate the reports:
 * {{{
 * object junit extends JUnitReport
 * }}}
 *
 * By default `JUnitReport` collects the test results in the output folder of the
 * `testCached` task. If you want to change this behavior you can define a different
 * `testDataSources` for example if you want to use the `test` command output.
 * {{{
 * object junit extends JUnitReport {
 *   override def testDataSources = "test"
 * }
 * }}}
 *
 * This provides you with the following reporting function:
 *
 * - mill __.testCached       # run tests for all modules
 * - mill junit.xmlReportAll  # generates testsuite reports in junit xml legacy format for all modules
 *
 * The aggregated report will be available at either `out/scoverage/htmlReportAll.dest/`
 * for html reports or `out/scoverage/xmlReportAll.dest/` for xml reports.
 */
trait JUnitReport extends Module {

  def testDataSources: String = "testCached"

  def xmlReportAll(
      evaluator: Evaluator
  ): Command[PathRef] = Target.command {
    val dataTasks = Resolve.Tasks.resolve(
      evaluator.rootModule,
      Seq("__.test.compile"),
      SelectMode.Separated
    ) match {
      case Left(err) => throw new Exception(err)
      case Right(tasks) =>
        tasks
          .asInstanceOf[Seq[NamedTask[CompilationResult]]]
          .map(task =>
            task.map { _ =>
              val testTaskSegments: Seq[Segment] =
                task.ctx.segments.value.dropRight(1).appended(Label(testDataSources))
              val testPaths =
                EvaluatorPaths.resolveDestPaths(evaluator.outPath, Segments(testTaskSegments))
              val testOutputPath = testPaths.dest / "out.json"
              val testResults = if (os.exists(testOutputPath)) {
                import upickle.default._
                val (_, results) = read[(String, Seq[TestResult])](testOutputPath.toNIO)
                results
              } else {
                Seq.empty
              }
              TestData(testOutputPath, testResults, Segments(testTaskSegments))
            }
          )
    }
    T.task {
      val testsData = T
        .sequence(dataTasks)()
        .filter { td =>
          if (td.result.isEmpty) {
            T.log.info(s"No test result in ${td.path.toNIO}")
          }
          td.result.nonEmpty
        }
      testsData.flatMap(asTestSuite).foreach(saveAsXML(T.log, T.dest))
      PathRef(T.dest)
    }
  }

  private def asTestSuite(testData: TestData): Iterable[TestSuite] = {
    val modified: FileTime = Files.getLastModifiedTime(testData.path.toNIO)
    val modifiedInstant: Instant = modified.toInstant
    TestData.asTestSuites(testData, modifiedInstant)
  }

  private def saveAsXML(log: Logger, outPath: os.Path)(ts: TestSuite): Unit = {
    val xmlPath = (outPath / s"TEST-${normalizeName(ts.name)}.xml").toNIO
    log.info(s"Save junit tests XML report: $xmlPath, with ${ts.tests} test(s)")
    XML.save(
      xmlPath.toAbsolutePath.toString,
      ts.asXML,
      "UTF-8",
      xmlDecl = true
    )
  }
  private def normalizeName(s: String) = s.replaceAll("""\s+""", "-")
}
