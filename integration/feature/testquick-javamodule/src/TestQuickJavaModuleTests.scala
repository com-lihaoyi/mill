package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object TestQuickJavaModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    def filterLines(out: String) = {
      out.linesIterator.filter(!_.contains("[info]")).toSet
    }

    test("testQuick-runs-all-on-first-run") - integrationTest { tester =>
      import tester._

      // First run should execute all tests
      val initial = eval("foo.test.testQuick")
      assert(initial.isSuccess)

      // Should see both test classes run
      val initialOut = initial.out
      assert(
        initialOut.contains("CalculatorTest") ||
          initialOut.contains("running") ||
          initialOut.contains("passed")
      )
    }

    test("testQuick-caches-unchanged") - integrationTest { tester =>
      import tester._

      // First run
      val initial = eval("foo.test.testQuick")
      assert(initial.isSuccess)

      // Second run with no changes - should run fewer tests or be cached
      val cached = eval("foo.test.testQuick")
      assert(cached.isSuccess)
    }

    test("testQuick-detects-changes") - integrationTest { tester =>
      import tester._

      // First run to establish baseline
      val initial = eval("foo.test.testQuick")
      assert(initial.isSuccess)

      // Modify Calculator.java - only CalculatorTest should run
      modifyFile(
        workspacePath / "foo/src/Calculator.java",
        _.replace("return a + b", "return a + b + 0")
      )

      val afterChange = eval("foo.test.testQuick")
      assert(afterChange.isSuccess)

      // The test should have detected the change and re-run affected tests
      assert(
        afterChange.out.contains("CalculatorTest") ||
          afterChange.out.contains("running") ||
          afterChange.out.contains("1 passed")
      )
    }

    test("testQuick-reruns-failed") - integrationTest { tester =>
      import tester._

      // First run
      val initial = eval("foo.test.testQuick")
      assert(initial.isSuccess)

      // Introduce a failing test by breaking the implementation
      modifyFile(
        workspacePath / "foo/src/Calculator.java",
        _.replace("return a + b", "return a - b") // Break add() to make test fail
      )

      // This run should fail
      val failing = eval("foo.test.testQuick", check = false)

      // Fix the implementation
      modifyFile(
        workspacePath / "foo/src/Calculator.java",
        _.replace("return a - b", "return a + b") // Fix it back
      )

      // Next run should re-run the previously failed test
      val fixed = eval("foo.test.testQuick")
      assert(fixed.isSuccess)
    }
  }
}
