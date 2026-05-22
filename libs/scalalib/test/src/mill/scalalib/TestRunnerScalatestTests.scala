package mill.scalalib

import mill.api.ExecResult
import mill.testkit.UnitTester
import sbt.testing.Status
import utest.*
import TestRunnerTestUtils.*

object TestRunnerScalatestTests extends TestSuite {

  override def tests: Tests = Tests {

    test("test") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval(testrunner.scalatest.testForked()).runtimeChecked
      assert(result.value.results.size == 10)
      junitReportIn(eval.outPath, "scalatest").shouldHave(10 -> Status.Success)
    }

    test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.scalatestZinc.discoveredTestClasses).runtimeChecked
      val expected = Set(
        "mill.scalalib.OuterTests",
        "mill.scalalib.ScalaTestSpec",
        "mill.scalalib.ScalaTestSpec2",
        "mill.scalalib.ScalaTestSpec3"
      )
      assert(result.value.toSet == expected)
      expected
    }

    test("discoveredTestClassesWithZinc") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.scalatestZinc.discoveredTestClasses).runtimeChecked
      val expected = Set(
        "mill.scalalib.OuterTests",
        "mill.scalalib.ScalaTestSpec",
        "mill.scalalib.ScalaTestSpec2",
        "mill.scalalib.ScalaTestSpec3"
      )
      assert(result.value.toSet == expected)
      expected
    }

    test("testOnly") - {
      scala.util.Using.resource(TestOnlyTester(_.scalatest)) { tester =>

        println("Case 1:")
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

        println("Case 2:")
        // Runs three test classes with 3 test cases each, and trigger test grouping
        tester.testOnly(
          Seq("*"),
          10,
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
              "group-1-mill.scalalib.ScalaTestSpec",
              "mill.scalalib.OuterTests",
              "mill.scalalib.ScalaTestSpec3",
              "test-report.xml"
            ),
            testrunnerWorkStealing.scalatest -> Set("worker-0", "test-classes", "test-report.xml")
          )
        )

        println("Case 3:")
        // include flag -n
        tester.testOnly(Seq("mill.scalalib.ScalaTestSpec", "--", "-n", "tagged"), 1)

        println("Case 4:")
        // exclude flag -l
        tester.testOnly(Seq("mill.scalalib.ScalaTestSpec", "--", "-l", "tagged"), 2)

        println("Case 5:")
        // Specific test flag -z
        tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec", "--", "-z", "should have size 0"),
          1
        )

        println("Case 6:")
        // Specific test flag -z with multiple suites
        tester.testOnly(Seq("*", "--", "-z", "should have size 0"), 3)

        println("Case 7:")
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
              "group-1-mill.scalalib.ScalaTestSpec",
              "mill.scalalib.OuterTests",
              "mill.scalalib.ScalaTestSpec3",
              "test-report.xml"
            ),
            testrunnerWorkStealing.scalatest -> Set("worker-0", "test-classes", "test-report.xml")
          )
        )

        println("Case 8:")
        // includeAndExclude
        tester.testOnly0 { (eval, mod) =>
          val Left(ExecResult.Failure(msg = msg)) =
            eval.apply(mod.scalatest.testOnly(
              "mill.scalalib.ScalaTestSpec",
              "--",
              "-n",
              "tagged",
              "-l",
              "tagged"
            )).runtimeChecked
          assert(msg.contains("Test selector does not match any test"))
        }
      }
    }
  }

}
