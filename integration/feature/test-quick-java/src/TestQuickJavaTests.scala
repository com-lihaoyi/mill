package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object TestQuickJavaTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("update app file") - integrationTest { tester =>
      import tester._

      // First run, all tests should run
      val firstRun = eval("app.test.testQuick")
      val firstRunOutLines = firstRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberCombinatorTests",
        "app.MyStringCombinatorTests",
        "app.MyStringDefaultValueTests",
        "app.MyNumberDefaultValueTests"
      ).foreach { expectedLines =>
        val exists = firstRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Second run, nothing should run because we're not changing anything
      val secondRun = eval("app.test.testQuick")
      assert(secondRun.out.isEmpty)

      // Third run, MyNumber.java changed, so MyNumberDefaultValueTests should run and fail
      modifyFile(
        workspacePath / "app" / "src" / "MyNumber.java",
        _.replace(
          "return new MyNumber(0);",
          "return new MyNumber(1);"
        )
      )
      val thirdRun = eval("app.test.testQuick")
      val thirdRunOutLines = thirdRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberDefaultValueTests"
      ).foreach { expectedLines =>
        val exists = thirdRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Fourth run, MyNumberDefaultValueTests failed, so it should run again
      // THIS IS THE CRITICAL TEST - verifies that failed JUnit tests are re-run
      val fourthRun = eval("app.test.testQuick")
      val fourthRunOutLines = fourthRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberDefaultValueTests"
      ).foreach { expectedLines =>
        val exists = fourthRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Fifth run, MyNumberDefaultValueTests was fixed, so it should run again
      modifyFile(
        workspacePath / "app" / "test" / "src" / "MyNumberDefaultValueTests.java",
        _.replace("assertEquals(new MyNumber(0), result);", "assertEquals(new MyNumber(1), result);")
      )
      val fifthRun = eval("app.test.testQuick")
      val fifthRunOutLines = fifthRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberDefaultValueTests"
      ).foreach { expectedLines =>
        val exists = fifthRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Sixth run, nothing should run because we're not changing anything
      val sixthRun = eval("app.test.testQuick")
      assert(sixthRun.out.isEmpty)
    }
    test("update lib file") - integrationTest { tester =>
      import tester._

      // First run, all tests should run
      val firstRun = eval("app.test.testQuick")
      val firstRunOutLines = firstRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberCombinatorTests",
        "app.MyStringCombinatorTests",
        "app.MyStringDefaultValueTests",
        "app.MyNumberDefaultValueTests"
      ).foreach { expectedLines =>
        val exists = firstRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Second run, nothing should run because we're not changing anything
      val secondRun = eval("app.test.testQuick")
      assert(secondRun.out.isEmpty)

      // Third run, Combinator.java changed syntactically (parameter order), should not run
      modifyFile(
        workspacePath / "lib" / "src" / "Combinator.java",
        _.replace("T combine(T a, T b);", "T combine(T b, T a);")
      )
      val thirdRun = eval("app.test.testQuick")
      assert(thirdRun.out.isEmpty)

      // Fourth run, Combinator.java changed semantically, should run MyNumberCombinatorTests & MyStringCombinatorTests
      modifyFile(
        workspacePath / "lib" / "src" / "Combinator.java",
        _.replace(
          "return combine(combine(a, b), c);",
          "return combine(a, combine(b, c));"
        )
      )
      val fourthRun = eval("app.test.testQuick")
      val fourthRunOutLines = fourthRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberCombinatorTests",
        "app.MyStringCombinatorTests"
      ).foreach { expectedLines =>
        val exists = fourthRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Fifth run, nothing should run because we're not changing anything
      val fifthRun = eval("app.test.testQuick")
      assert(fifthRun.out.isEmpty)
    }
  }
}
