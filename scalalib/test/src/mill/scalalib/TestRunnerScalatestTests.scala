package mill.scalalib

import mill.api.Result
import mill.testkit.UnitTester
import sbt.testing.Status
import utest._

object TestRunnerScalatestTests extends TestSuite {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
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

      test("all") - tester.testOnly(
        Seq("*"),
        9,
        Map(
          testrunner.scalatest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          // When there are multiple test groups some with multiple test classes, we put each
          // test group in a subfolder with the index of the group, and for any test groups
          // with only one test class we append the name of the class
          testrunnerGrouping.scalatest -> Set(
            "group-0-mill.scalalib.ScalaTestSpec",
            "mill.scalalib.ScalaTestSpec3",
            "test-report.xml"
          )
        )
      )
    }
  }
}

