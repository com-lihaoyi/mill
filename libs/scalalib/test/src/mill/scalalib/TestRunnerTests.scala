package mill.scalalib

import mill.api.ExecResult
import mill.testkit.UnitTester
import sbt.testing.Status
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}

object TestRunnerTests extends TestSuite {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {

    test("doneMessage") {
      test("failure") {
        val outStream = new ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = new PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Left(ExecResult.Failure(msg)) =
            eval(testrunner.doneMessageFailure.testForked()): @unchecked
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test failure done message"))
          junitReportIn(eval.outPath, "doneMessageFailure").shouldHave(1 -> Status.Failure)
        }
      }

      test("success") {
        val outStream = new ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = new PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Right(_) = eval(testrunner.doneMessageSuccess.testForked()): @unchecked
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test success done message"))
        }
      }

      test("null") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(_) = eval(testrunner.doneMessageNull.testForked()): @unchecked
      }
    }
  }

}
