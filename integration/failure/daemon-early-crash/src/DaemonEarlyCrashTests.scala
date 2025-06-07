package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object DaemonEarlyCrashTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("check") - integrationTest { tester =>
      import tester._
      if (daemonMode) {
        val res = eval("version", env = Map("MILL_DAEMON_CRASH" -> "true"), timeout = 10000L)
        assert(res.exitCode == 1)
        assert(res.err.contains("Mill daemon exited!"))
        assert(res.err.contains("Mill daemon early crash requested"))
      } else
        "Disabled in non-daemon mode"
    }
  }
}
