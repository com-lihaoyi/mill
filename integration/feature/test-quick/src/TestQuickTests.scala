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
      assert(firstRun.out.contains("FooTest3"))

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

    test("testQuickMultiModule") - integrationTest { tester =>
      import tester._

      // First run: all tests should execute including FooTest3 which depends on bar module
      val firstRun = eval("foo.test.testQuick")
      assert(firstRun.isSuccess)
      assert(firstRun.out.contains("FooTest3"))

      // Second run: no tests (nothing changed)
      val secondRun = eval("foo.test.testQuick")
      assert(secondRun.isSuccess)
      assert(!secondRun.out.contains("Running Test Class"))

      // Modify the bar module - this should trigger FooTest3 to re-run
      modifyFile(
        workspacePath / "bar" / "src" / "Bar.java",
        _.replace("return 42;", "return 43;")
      )

      // Third run: FooTest3 should run and fail (Bar.value() changed from 42 to 43)
      val thirdRun = eval("foo.test.testQuick")
      assert(!thirdRun.isSuccess)
      assert(thirdRun.out.contains("FooTest3"))

      // Fix the bar module
      modifyFile(
        workspacePath / "bar" / "src" / "Bar.java",
        _.replace("return 43;", "return 42;")
      )

      // Fourth run: FooTest3 should pass now
      val fourthRun = eval("foo.test.testQuick")
      assert(fourthRun.isSuccess)
      assert(fourthRun.out.contains("FooTest3"))

      // Fifth run: no tests (everything passed)
      val fifthRun = eval("foo.test.testQuick")
      assert(fifthRun.isSuccess)
      assert(!fifthRun.out.contains("Running Test Class"))
    }

    test("testQuickScala") - integrationTest { tester =>
      import tester._

      // First run: all Scala tests should execute
      val firstRun = eval("qux.test.testQuick")
      assert(firstRun.isSuccess)
      assert(firstRun.out.contains("qux.QuxTests"))

      // Second run: no tests (nothing changed)
      val secondRun = eval("qux.test.testQuick")
      assert(secondRun.isSuccess)
      assert(!secondRun.out.contains("qux.QuxTests"))

      // Modify Qux.scala - change hello() return value
      modifyFile(
        workspacePath / "qux" / "src" / "qux" / "Qux.scala",
        _.replace("\"Hello\"", "\"Hi\"")
      )

      // Third run: tests should run and fail (hello() changed)
      val thirdRun = eval("qux.test.testQuick")
      assert(!thirdRun.isSuccess)
      assert(thirdRun.out.contains("qux.QuxTests"))

      // Fix the code
      modifyFile(
        workspacePath / "qux" / "src" / "qux" / "Qux.scala",
        _.replace("\"Hi\"", "\"Hello\"")
      )

      // Fourth run: tests should pass
      val fourthRun = eval("qux.test.testQuick")
      assert(fourthRun.isSuccess)
      assert(fourthRun.out.contains("qux.QuxTests"))

      // Fifth run: no tests (everything passed)
      val fifthRun = eval("qux.test.testQuick")
      assert(fifthRun.isSuccess)
      assert(!fifthRun.out.contains("qux.QuxTests"))
    }
  }
}
