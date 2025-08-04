package mill.scalalib

import mill.api.ExecResult
import mill.javalib.testrunner.TestResult
import mill.testkit.UnitTester
import sbt.testing.Status
import utest.*

object TestRunnerUtestTests extends TestSuite {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("test case lookup") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.utest.testForked()): @unchecked
      val test = result.value.asInstanceOf[(String, Seq[TestResult])]
      assert(
        test._2.size == 3
      )
      junitReportIn(eval.outPath, "utest").shouldHave(3 -> Status.Success)
    }
    test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.utest.discoveredTestClasses): @unchecked
      val expected = Seq(
        "mill.scalalib.BarTests",
        "mill.scalalib.FooTests",
        "mill.scalalib.FoobarTests"
      )
      assert(result.value == expected)
      expected
    }
    test("testOnly") - {
      scala.util.Using.resource(new TestOnlyTester(_.utest)) { tester =>
        // suffix
        tester.testOnly(Seq("*arTests"), 2)
        // prefix
        tester.testOnly(Seq("mill.scalalib.FooT*"), 1)
        // exactly
        tester.testOnly(
          Seq("mill.scalalib.FooTests"),
          1, {
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
              testrunner.utest -> results,
              // When there is only one test group with test classes, we do not put it in a subfolder
              testrunnerGrouping.utest -> results,
              // When there is only one test group with test classes, we do not run workers
              testrunnerWorkStealing.utest -> results
            )
          }
        )
        // multi
        tester.testOnly(
          Seq("*Bar*", "*bar*"),
          2,
          Map(
            testrunner.utest -> Set(
              "claim",
              "claim.log",
              "out.json",
              "result.log",
              "sandbox",
              "test-classes",
              "test-report.xml",
              "testargs"
            ),
            // When there are multiple test groups with one test class each, we
            // put each test group in a subfolder with the number of the class
            testrunnerGrouping.utest -> Set(
              "mill.scalalib.BarTests",
              "mill.scalalib.FoobarTests",
              "test-report.xml"
            ),
            testrunnerWorkStealing.utest -> Set("worker-0", "test-classes", "test-report.xml")
          )
        )
        // all
        tester.testOnly(
          Seq("*"),
          3,
          Map(
            testrunner.utest -> Set(
              "claim",
              "claim.log",
              "out.json",
              "result.log",
              "sandbox",
              "test-classes",
              "test-report.xml",
              "testargs"
            ),
            // When there are multiple test groups some with multiple test classes, we put each
            // test group in a subfolder with the index of the group, and for any test groups
            // with only one test class we append the name of the class
            testrunnerGrouping.utest -> Set(
              "group-0-mill.scalalib.BarTests",
              "mill.scalalib.FoobarTests",
              "test-report.xml"
            ),
            testrunnerWorkStealing.utest -> Set("worker-0", "test-classes", "test-report.xml")
          )
        )
        // noMatch
        tester.testOnly0 { (eval, mod) =>
          val Left(ExecResult.Failure(msg)) =
            eval.apply(mod.utest.testOnly("noMatch", "noMatch*2")): @unchecked
          assert(
            msg == "Test selector does not match any test: noMatch noMatch*2\nRun discoveredTestClasses to see available tests"
          )
        }
      }
    }

  }

}
