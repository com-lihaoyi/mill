package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ShutdownExitCodeTests extends UtestIntegrationTestSuite {
  // Ensure that `shutdown` succeeds even if the prior command failed
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      mill.constants.DebugLog.println("ShutdownExitCodeTests resolve")
      val result1 = tester.eval(("resolve", "_"))
      mill.constants.DebugLog.println("ShutdownExitCodeTests done")
      assert(result1.isSuccess == true)

      mill.constants.DebugLog.println("ShutdownExitCodeTests shutdown")
      val result2 = tester.eval("shutdown")
      mill.constants.DebugLog.println("ShutdownExitCodeTests shutdown done")
      assert(result2.isSuccess == true)

      mill.constants.DebugLog.println("ShutdownExitCodeTests doesnt-exit")
      val result3 = tester.eval("doesnt-exit")
      mill.constants.DebugLog.println("ShutdownExitCodeTests doesnt-exit done")
      assert(result3.exitCode == 1)

      val result4 = tester.eval("shutdown")
      assert(result4.isSuccess == true)
    }
  }
}
