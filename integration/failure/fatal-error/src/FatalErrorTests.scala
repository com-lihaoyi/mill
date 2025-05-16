package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object FatalErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("fatalTask")

      assert(res.isSuccess == false)
      assert(res.err.contains("""java.lang.LinkageError: CUSTOM FATAL ERROR IN TASK"""))

      // Only run this test in client-server mode, since workers are not shutdown
      // with `close()` in no-server mode so the error does not trigger
      if (daemonMode) {
        // This worker invalidates re-evaluates every time due to being dependent on
        // an upstream `Task.Input`. Make sure that a fatal error in the `close()`
        // call does not hang the Mill process
        tester.eval("fatalCloseWorker")
        val res3 = tester.eval("fatalCloseWorker")
        assert(res3.err.contains("""java.lang.LinkageError: CUSTOM FATAL ERROR"""))
      }
    }
  }
}
