package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object TestTaskBatchTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("forkedBatch") - integrationTest { tester =>
      val res = tester.eval("app.test")
      assert(res.isSuccess)
      assert(res.out.contains("batch task ran with MY_ENV_VALUE"))

      val selected = tester.eval((
        "app.test.testOnly",
        "com.example.SharedResource",
        "com.example.BatchSuiteTest"
      ))
      assert(selected.isSuccess)
      assert(selected.out.contains("batch task ran with MY_ENV_VALUE"))
    }
  }
}
