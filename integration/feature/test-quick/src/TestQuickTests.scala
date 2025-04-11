package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object TestQuickTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("update app file") - integrationTest { tester =>
      import tester._

      // First run, all tests should run
      val firstRun = eval("app.test.testQuick")
      val firstRunOutLines = firstRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberCombinatorTests.simple",
        "app.MyStringCombinatorTests.simple",
        "app.MyStringDefaultValueTests.simple",
        "app.MyNumberDefaultValueTests.simple"
      ).foreach { expectedLines =>
        val exists = firstRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Second run, nothing should run because we're not changing anything
      val secondRun = eval("app.test.testQuick")
      assert(secondRun.out.isEmpty)

      // Third run, MyNumber.scala changed, so MyNumberDefaultValueTests should run
      modifyFile(
        workspacePath / "app" / "src" / "MyNumber.scala",
        _.replace(
          "def defaultValue: MyNumber = MyNumber(0)",
          "def defaultValue: MyNumber = MyNumber(1)"
        )
      )
      val thirdRun = eval("app.test.testQuick")
      val thirdRunOutLines = thirdRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberDefaultValueTests.simple"
      ).foreach { expectedLines =>
        val exists = thirdRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Fourth run, MyNumberDefaultValueTests was failed, so it should run again
      val fourthRun = eval("app.test.testQuick")
      val fourthRunOutLines = fourthRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberDefaultValueTests.simple"
      ).foreach { expectedLines =>
        val exists = fourthRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Fifth run, MyNumberDefaultValueTests was fixed, so it should run again
      modifyFile(
        workspacePath / "app" / "test" / "src" / "MyNumberDefaultValueTests.scala",
        _.replace("assert(result == MyNumber(0))", "assert(result == MyNumber(1))")
      )
      val fifthRun = eval("app.test.testQuick")
      val fifthRunOutLines = fifthRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberDefaultValueTests.simple"
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
        "app.MyNumberCombinatorTests.simple",
        "app.MyStringCombinatorTests.simple",
        "app.MyStringDefaultValueTests.simple",
        "app.MyNumberDefaultValueTests.simple"
      ).foreach { expectedLines =>
        val exists = firstRunOutLines.exists(_.contains(expectedLines))
        assert(exists)
      }

      // Second run, nothing should run because we're not changing anything
      val secondRun = eval("app.test.testQuick")
      assert(secondRun.out.isEmpty)

      // Third run, Combinator.scala changed syntactically, should not run
      modifyFile(
        workspacePath / "lib" / "src" / "Combinator.scala",
        _.replace("def combine(a: T, b: T): T", "def combine(b: T, a: T): T")
      )
      val thirdRun = eval("app.test.testQuick")
      assert(thirdRun.out.isEmpty)

      // Fourth run, Combinator.scala changed sematically, should run MyNumberCombinatorTests & MyStringCombinatorTests
      modifyFile(
        workspacePath / "lib" / "src" / "Combinator.scala",
        _.replace("def combine2(a: T, b: T, c: T): T = combine(combine(a, b), c)", "def combine2(a: T, b: T, c: T): T = combine(a, combine(b, c))")
      )
      val fourthRun = eval("app.test.testQuick")
      val fourthRunOutLines = fourthRun.out.linesIterator.toSeq
      Seq(
        "app.MyNumberCombinatorTests.simple",
        "app.MyStringCombinatorTests.simple"
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
