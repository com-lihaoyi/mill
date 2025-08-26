package mill.scalalib

import mill.api.ExecResult
import mill.testkit.UnitTester
import sbt.testing.Status
import utest.*

object TestRunnerScalatestTests extends TestSuite {
  import TestRunnerTestUtils.*
  override def tests: Tests = Tests {
    test("test") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval(testrunner.scalatest.testForked()): @unchecked
      assert(result.value.results.size == 9)
      junitReportIn(eval.outPath, "scalatest").shouldHave(9 -> Status.Success)
    }
    test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.scalatest.discoveredTestClasses): @unchecked
      val expected = Seq(
        "mill.scalalib.ScalaTestSpec",
        "mill.scalalib.ScalaTestSpec2",
        "mill.scalalib.ScalaTestSpec3"
      )
      assert(result.value == expected)
      expected
    }

    test("testOnly") - {
      scala.util.Using.resource(new TestOnlyTester(_.scalatest)) { tester =>

        // Run all tests re-using the same `tester` object for performance reasons
        // singleClass
        tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec"),
          3, {
            val results = Set(
              "claim",
              "claim.log",
              "out.json",
              "result.log",
              "sandbox",
              "test-classes",
              "test-report.xml",
              "testargs"
            )

            Map(
              testrunner.scalatest -> results,
              // No test grouping is triggered because we only run one test class
              testrunnerGrouping.scalatest -> results,
              // No workers are triggered because we only run one test class
              testrunnerWorkStealing.scalatest -> results
            )
          }
        )

        // Runs three test classes with 3 test cases each, and trigger test grouping
        tester.testOnly(
          Seq("*"),
          9,
          Map(
            testrunner.scalatest -> Set(
              "claim",
              "claim.log",
              "out.json",
              "result.log",
              "sandbox",
              "test-classes",
              "test-report.xml",
              "testargs"
            ),
            testrunnerGrouping.scalatest -> Set(
              "group-0-mill.scalalib.ScalaTestSpec",
              "mill.scalalib.ScalaTestSpec3",
              "test-report.xml"
            ),
            testrunnerWorkStealing.scalatest -> Set("worker-0", "test-classes", "test-report.xml")
          )
        )
        // include flag -n
        tester.testOnly(Seq("mill.scalalib.ScalaTestSpec", "--", "-n", "tagged"), 1)
        // exclude flag -l
        tester.testOnly(Seq("mill.scalalib.ScalaTestSpec", "--", "-l", "tagged"), 2)
        // Specific test flag -z
        tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec", "--", "-z", "should have size 0"),
          1
        )

        // Specific test flag -z with multiple suites
        tester.testOnly(Seq("*", "--", "-z", "should have size 0"), 3)

        // Scalatest runs all test suites even when `-z` only finds matching tests
        // in one of them. This is just how Scalatest works, and you are expected to
        // sue `testOnly` with a selector before the `--` to select the class you want
        tester.testOnly(
          Seq("*", "--", "-z", "A Set 2"),
          3,
          Map(
            testrunner.scalatest -> Set(
              "claim",
              "claim.log",
              "out.json",
              "result.log",
              "sandbox",
              "test-classes",
              "test-report.xml",
              "testargs"
            ),
            testrunnerGrouping.scalatest -> Set(
              "group-0-mill.scalalib.ScalaTestSpec",
              "mill.scalalib.ScalaTestSpec3",
              "test-report.xml"
            ),
            testrunnerWorkStealing.scalatest -> Set("worker-0", "test-classes", "test-report.xml")
          )
        )

        // includeAndExclude
        tester.testOnly0 { (eval, mod) =>
          val Left(ExecResult.Failure(msg)) =
            eval.apply(mod.scalatest.testOnly(
              "mill.scalalib.ScalaTestSpec",
              "--",
              "-n",
              "tagged",
              "-l",
              "tagged"
            )): @unchecked
          assert(msg.contains("Test selector does not match any test"))
        }
      }
    }
  }

}
