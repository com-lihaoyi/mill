package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ShutdownExitCodeTests extends UtestIntegrationTestSuite {
  // Ensure that `shutdown` succeeds even if the prior command failed
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      val result1 = tester.eval("doesnt-exit")
      assert(result1.isSuccess == false)
      val result2 = tester.eval("shutdown")
      assert(result2.isSuccess == true)
    }
  }
}
