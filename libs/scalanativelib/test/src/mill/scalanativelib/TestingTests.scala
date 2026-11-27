package mill.scalanativelib

import mill.*
import mill.api.{ExecResult, ExecutionPaths}
import mill.scalanativelib.api.*
import mill.testkit.UnitTester
import scala.xml.XML
import utest.*

object TestingTests extends TestSuite {
  import CompileRunTests.*
  def tests: Tests = Tests {

    test("test") - {
      testAllMatrix { (scalaVersion, scalaNativeVersion, mode) =>
        UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
          val testTask =
            HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.testForked()
          val Left(_: ExecResult.Failure[?]) = eval(testTask).runtimeChecked

          // A compilation or setup failure cannot produce a report for the fixture's tests.
          val reportPath = ExecutionPaths.resolve(eval.outPath, testTask).dest / "test-report.xml"
          assert(os.exists(reportPath))
          val report = XML.loadFile(reportPath.toIO)
          val failed = (report \\ "testcase").filter(testCase => (testCase \ "failure").nonEmpty)

          assert(
            (report \@ "tests") == "6",
            (report \@ "errors") == "0",
            (report \@ "skipped") == "0",
            failed.map(testCase => (testCase \@ "classname", testCase \@ "name")) ==
              Seq(("hellotest.ArgsParserTests", "two"))
          )
        }
      }
    }

    def checkInheritedTasks[A](task: ScalaNativeModule => T[A], expected: A) =
      UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Right(mainResult) = eval(task(HelloNativeWorld.inherited)).runtimeChecked
        val Right(testResult) = eval(task(HelloNativeWorld.inherited.test)).runtimeChecked
        assert(mainResult.value == expected)
        assert(testResult.value == expected)
      }
    test("test-scalacOptions") {
      checkInheritedTasks(_.scalacOptions, Seq("-deprecation"))
    }
  }

}
