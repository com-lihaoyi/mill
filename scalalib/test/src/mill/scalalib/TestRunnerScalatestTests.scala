package mill.scalalib

import mill.api.Result
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.{Agg, T, Task}
import os.Path
import sbt.testing.Status
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.xml.{Elem, NodeSeq, XML}


object TestRunnerScalatestTests extends TestSuite {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("ScalaTest") {
      test("test") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval(testrunner.scalatest.test())
        assert(result.value._2.size == 3)
        junitReportIn(eval.outPath, "scalatest").shouldHave(3 -> Status.Success)
      }
      test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(testrunner.scalatest.discoveredTestClasses)
        val expected = Seq("mill.scalalib.ScalaTestSpec")
        assert(result.value == expected)
        expected
      }

      test("testOnly") - {
        val tester = new TestOnlyTester(_.scalatest)

        test("all") - tester.testOnly(Seq("mill.scalalib.ScalaTestSpec"), 3)
        test("include") - tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec", "--", "-n", "tagged"),
          1
        )
        test("exclude") - tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec", "--", "-l", "tagged"),
          2
        )
        test("includeAndExclude") - tester.testOnly0 { (eval, mod) =>
          val Left(Result.Failure(msg, _)) =
            eval.apply(mod.scalatest.testOnly(
              "mill.scalalib.ScalaTestSpec",
              "--",
              "-n",
              "tagged",
              "-l",
              "tagged"
            ))
          assert(msg.contains("Test selector does not match any test"))
        }
      }
    }

  }

}

