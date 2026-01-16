package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object InteractiveTasksTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {

    test("daemon") - integrationTest { tester =>
      // This test must be run in daemon mode for the re-run-interactive-tests-with-no-daemon
      // logic to be meaningful
      assert(InteractiveTasksTests.this.daemonMode)
      import tester._

      // Non-interactive tasks run in daemon mode
      val res1 = eval("hello")
      assert(res1.isSuccess)
      val output1 = res1.result.out.text()
      assert(output1.contains("NORMAL_TASK_EXECUTED"))
      assert(output1.contains("IS_DAEMON_MODE=true"))
      assert(output1.contains("IS_NO_DAEMON_MODE=false"))

      // Interactive tasks should be skipped in daemon mode
      // and then automatically re-run in no-daemon mode by the launcher
      val res2 = eval("interactiveHello")
      assert(res2.isSuccess)
      val output2 = res2.result.out.text()
      // The interactive task should have been executed (in no-daemon mode)
      assert(output2.contains("INTERACTIVE_TASK_EXECUTED"))
      // When re-run in no-daemon mode, it should NOT be in daemon mode
      assert(output2.contains("IS_DAEMON_MODE=false"))
      assert(output2.contains("IS_NO_DAEMON_MODE=true"))
    }
  }
}
