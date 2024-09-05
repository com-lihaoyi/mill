package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ModuleOutsideTopLevelModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "Modules, Targets and Commands can only be defined within a mill Module"
        )
      )
      assert(
        res.err.contains(
          "object invalidModule extends Module"
        )
      )
    }
  }
}
