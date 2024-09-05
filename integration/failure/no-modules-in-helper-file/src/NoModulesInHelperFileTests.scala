package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object NoModulesInHelperFileTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(
        res.err.contains("Modules, Targets and Commands can only be defined within a mill Module")
      )
      assert(res.err.contains("object foo extends Module"))
    }
  }
}
