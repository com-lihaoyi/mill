package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object ThreadLocalsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("cache") - integrationTest { tester =>
      import tester._

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
