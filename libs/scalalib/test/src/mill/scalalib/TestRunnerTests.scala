package mill.scalalib

import mill.testkit.UnitTester
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object TestRunnerTests extends TestSuite {
  import TestRunnerTestUtils.*

  override def tests: Tests = Tests {

    test("doneMessage") {
      test("failure") {
        val outStream = new ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = new PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Right(UnitTester.Result(("test failure done message", Nil), 90)) =
            eval.apply(testrunner.doneMessageFailure.testForked()): @unchecked
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test failure done message"))
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
