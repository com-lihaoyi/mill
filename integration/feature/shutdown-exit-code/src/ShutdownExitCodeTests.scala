package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ShutdownExitCodeTests extends UtestIntegrationTestSuite {
  // Ensure that `shutdown` succeeds even if the prior command failed
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      val result1 = tester.eval(("resolve", "_"))
      assert(result1.isSuccess == true)

      val result2 = tester.eval("shutdown")
      assert(result2.isSuccess == true)

      val result3 = tester.eval("doesnt-exit")
      assert(result3.exitCode == 1)

      val result4 = tester.eval("shutdown")
      assert(result4.isSuccess == true)
    }
  }
}
