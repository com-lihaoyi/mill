package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object TestQuickJavaModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    test("testQuick") - integrationTest { tester =>
      import tester.*

      // First run - all tests should execute
      val initial = eval("foo.test.testQuick")
      assert(initial.isSuccess)

      // Verify all tests ran
      val initialOut = initial.out
      assert(initialOut.contains("CalculatorTest"))
      assert(initialOut.contains("StringUtilsTest"))

      // Second run with no changes - no tests should run
      val cached = eval("foo.test.testQuick")
      assert(cached.isSuccess)
      assert(cached.out.contains("No tests to run") || !cached.out.contains("CalculatorTest"))

      // Modify Calculator.java - only CalculatorTest should run
      modifyFile(
        workspacePath / "foo/src/Calculator.java",
        _.replace("return a + b;", "return a + b + 0;")
      )

      val afterChange = eval("foo.test.testQuick")
      assert(afterChange.isSuccess)
      // CalculatorTest should run since Calculator changed
      assert(afterChange.out.contains("CalculatorTest") || afterChange.out.contains("1 of"))

      // Verify methodCodeHashSignatures task works
      val signatures = eval("foo.test.methodCodeHashSignatures")
      assert(signatures.isSuccess)
    }

    test("testQuick-with-failure") - integrationTest { tester =>
      import tester.*

      // First run - all tests pass
      val initial = eval("foo.test.testQuick")
      assert(initial.isSuccess)

      // Modify to make a test fail
      modifyFile(
        workspacePath / "foo/src/Calculator.java",
        _.replace("return a + b;", "return a + b + 1;") // Wrong result
      )

      val withFailure = eval("foo.test.testQuick")
      // Test should run and fail
      assert(!withFailure.isSuccess || withFailure.out.contains("Failure"))

      // Fix the bug
      modifyFile(
        workspacePath / "foo/src/Calculator.java",
        _.replace("return a + b + 1;", "return a + b;") // Fixed
      )

      // The failed test should re-run to verify the fix
      val afterFix = eval("foo.test.testQuick")
      assert(afterFix.isSuccess)
      assert(afterFix.out.contains("CalculatorTest"))
    }
  }
}
