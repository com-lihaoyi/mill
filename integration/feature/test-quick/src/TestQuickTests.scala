package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object TestQuickTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("testQuick") - integrationTest { tester =>
      import tester._

      // First run: all tests should execute
      val firstRun = eval("foo.test.testQuick")
      assert(firstRun.isSuccess)
      assert(firstRun.out.contains("FooTest1"))
      assert(firstRun.out.contains("FooTest2"))

      // Second run: no tests should execute (nothing changed)
      val secondRun = eval("foo.test.testQuick")
      assert(secondRun.isSuccess)
      // No test output means no tests ran
      assert(!secondRun.out.contains("Running Test Class"))

      // Modify bar() to return 2 - this will cause FooTest2 to fail
      modifyFile(
        workspacePath / "foo" / "src" / "Foo.java",
        _.replace("return 1;", "return 2;")
      )

      // Third run: FooTest2 should run and fail (bar() changed and affects FooTest2)
      val thirdRun = eval("foo.test.testQuick")
      assert(!thirdRun.isSuccess)
      assert(thirdRun.out.contains("FooTest2"))
      // FooTest1 may or may not run depending on callgraph - it shouldn't since qux() didn't change

      // Fourth run: FooTest2 should re-run (it failed, so it should re-run even without changes)
      // This is the CRITICAL test for the JUnit bug fix
      val fourthRun = eval("foo.test.testQuick")
      assert(!fourthRun.isSuccess)
      assert(fourthRun.out.contains("FooTest2"))

      // Fix the code: restore bar() to return 1
      modifyFile(
        workspacePath / "foo" / "src" / "Foo.java",
        _.replace("return 2;", "return 1;")
      )

      // Fifth run: FooTest2 should run and pass now
      val fifthRun = eval("foo.test.testQuick")
      assert(fifthRun.isSuccess)
      assert(fifthRun.out.contains("FooTest2"))

      // Sixth run: no tests should execute (everything passed and nothing changed)
      val sixthRun = eval("foo.test.testQuick")
      assert(sixthRun.isSuccess)
      // No test output means no tests ran
      assert(!sixthRun.out.contains("Running Test Class"))
    }
  }
}
