package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object ThreadLocalsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("cache") - integrationTest { tester =>
      import tester.*

      // Worker replacement (and thus the close() that emits the messages
      // checked below) only happens within a single daemon's lifetime: the
      // second invocation observes the env-driven identity-hash change and
      // closes the prior worker. In `--no-daemon` mode each invocation is a
      // fresh JVM with an empty worker cache, so no close() ever runs.
      if (tester.daemonMode) {
        eval("demo.demoCrash")
        val res = eval("demo.demoCrash", env = Map("WORKER_ENV" -> "1"))
        assert(res.out.contains(
          s"Worker is closing1 $workspacePath/out/demo/myWorker.dest class mill.exec.GroupExecution$$ExecutionChecker"
        ))
        assert(res.out.contains(s"Worker is closing2"))
        assert(res.out.contains(s"Worker is closing3"))
        assert(res.err.contains(s"Worker is closing4"))
        assert(res.err.contains(s"Worker is closing5"))
      }
    }
  }
}
