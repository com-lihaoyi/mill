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
      assert(result.value._2.size == 9)
      junitReportIn(eval.outPath, "scalatest").shouldHave(9 -> Status.Success)
    }
    test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.scalatest.discoveredTestClasses)
      val expected = Seq(
        "mill.scalalib.ScalaTestSpec",
        "mill.scalalib.ScalaTestSpec2",
        "mill.scalalib.ScalaTestSpec3"
      )
      assert(result.value == expected)
      expected
    }

    test("testOnly") - {
      val tester = new TestOnlyTester(_.scalatest)

      test("singleClass") - tester.testOnly(
        Seq("mill.scalalib.ScalaTestSpec"),
        3,
        Map(
          // No test grouping is triggered because we only run one test class
          testrunner.scalatest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          testrunnerGrouping.scalatest -> Set("out.json", "sandbox", "test-report.xml", "testargs")
        )
      )

      // Runs three test classes with 3 test cases each, and trigger test grouping
      test("multiClass") - tester.testOnly(
        Seq("*"),
        9,
        Map(
          testrunner.scalatest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          testrunnerGrouping.scalatest -> Set(
            "group-0-mill.scalalib.ScalaTestSpec",
            "mill.scalalib.ScalaTestSpec3",
            "test-report.xml"
          )
        )
      )
      test("include") - tester.testOnly(
        Seq("mill.scalalib.ScalaTestSpec", "--", "-n", "tagged"),
        1
      )
      test("exclude") - tester.testOnly(
        Seq("mill.scalalib.ScalaTestSpec", "--", "-l", "tagged"),
        2
      )
      test("specific") - tester.testOnly(
        Seq("mill.scalalib.ScalaTestSpec", "--", "-z", "should have size 0"),
        1
      )
      test("specificMulti") - tester.testOnly(
        Seq("*", "--", "-z", "should have size 0"),
        3
      )

      // Scalatest runs all test suites even when `-z` only finds matching tests
      // in one of them. This is just how Scalatest works, and you are expected to
      // sue `testOnly` with a selector before the `--` to select the class you want
      test("specificMulti2") - tester.testOnly(
        Seq("*", "--", "-z", "A Set 2"),
        3,
        Map(
          testrunner.scalatest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          testrunnerGrouping.scalatest -> Set(
            "group-0-mill.scalalib.ScalaTestSpec",
            "mill.scalalib.ScalaTestSpec3",
            "test-report.xml"
          )
        )
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
