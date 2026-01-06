package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object NonExclusiveCommandEvaluator extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester.*
      val res = eval(("customPlanCommand", "taskCallingCommand"))
      assert(res.isSuccess == false)
      assert(
        res.err.contains(
          "No evaluator available here; Evaluator is only available in exclusive commands"
        )
      )
    }
  }
}
