package mill.scalalib

import mill.api.Result
import mill.testkit.UnitTester
import sbt.testing.Status
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}


object TestRunnerUtestTests extends TestSuite {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("test case lookup") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.utest.test())
      val test = result.value.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
      assert(
        test._2.size == 3
      )
      junitReportIn(eval.outPath, "utest").shouldHave(3 -> Status.Success)
    }
    test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.utest.discoveredTestClasses)
      val expected = Seq(
        "mill.scalalib.BarTests",
        "mill.scalalib.FooTests",
        "mill.scalalib.FoobarTests"
      )
      assert(result.value == expected)
      expected
    }
    test("testOnly") - {
      val tester = new TestOnlyTester(_.utest)
      test("suffix") - tester.testOnly(Seq("*arTests"), 2)
      test("prefix") - tester.testOnly(Seq("mill.scalalib.FooT*"), 1)
      test("exactly") - tester.testOnly(
        Seq("mill.scalalib.FooTests"),
        1,
        Map(
          testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          // When there is only one test group with test classes, we do not put it in a subfolder
          testrunnerGrouping.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs")
        )
      )
      test("multi") - tester.testOnly(
        Seq("*Bar*", "*bar*"),
        2,
        Map(
          testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          // When there are multiple test groups with one test class each, we
          // put each test group in a subfolder with the number of the class
          testrunnerGrouping.utest -> Set(
            "mill.scalalib.BarTests",
            "mill.scalalib.FoobarTests",
            "test-report.xml"
          )
        )
      )
      test("all") - tester.testOnly(
        Seq("*"),
        3,
        Map(
          testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          // When there are multiple test groups some with multiple test classes, we put each
          // test group in a subfolder with the index of the group, and for any test groups
          // with only one test class we append the name of the class
          testrunnerGrouping.utest -> Set(
            "group-0-mill.scalalib.BarTests",
            "mill.scalalib.FoobarTests",
            "test-report.xml"
          )
        )
      )

      test("testArgFilter") - tester.testOnly(
        Seq("*", "--", "mill.scalalib.FoobarTests.test"),
        1,
        // Make sure filtering via test arguments behaves similarly as filtering via
        // glob selectors, even though it needs to be done by the test framework rather
        // than Mill. In particular, when only one test class ends up selected, we do not
        // create folders/output-folders/etc. for the other test groups whose tests all
        // get filtered out

        Map(
          testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
          testrunnerGrouping.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
        )
      )
      test("noMatch") - tester.testOnly0 { (eval, mod) =>
        val Left(Result.Failure(msg, _)) =
          eval.apply(mod.utest.testOnly("noMatch", "noMatch*2"))
        assert(
          msg == "Test selector does not match any test: noMatch noMatch*2\nRun discoveredTestClasses to see available tests"
        )
      }
    }

    test("doneMessage") {
      test("failure") {
        val outStream = new ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = new PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Left(Result.Failure(msg, _)) = eval(testrunner.doneMessageFailure.test())
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
          val Right(_) = eval(testrunner.doneMessageSuccess.test())
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test success done message"))
        }
      }

      test("null") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(_) = eval(testrunner.doneMessageNull.test())
      }
    }
  }

}
