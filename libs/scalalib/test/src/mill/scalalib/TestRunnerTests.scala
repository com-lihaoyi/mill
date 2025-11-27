package mill.scalalib

import mill.testkit.UnitTester
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object TestRunnerTests extends TestSuite {
  import TestRunnerTestUtils.*

  override def tests: Tests = Tests {

    test("doneMessage") {
      test("failure") {
        val outStream = ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Right(UnitTester.Result(("test failure done message", Nil), _)) =
            eval.apply(testrunner.doneMessageFailure.testForked()): @unchecked
          val stdout = String(outStream.toByteArray)
          assert(stdout.contains("test failure done message"))
        }
      }

      test("success") {
        val outStream = ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Right(_) = eval(testrunner.doneMessageSuccess.testForked()): @unchecked
          val stdout = String(outStream.toByteArray)
          assert(stdout.contains("test success done message"))
        }
      }

      test("null") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(_) = eval(testrunner.doneMessageNull.testForked()): @unchecked
      }
    }
  }

}
