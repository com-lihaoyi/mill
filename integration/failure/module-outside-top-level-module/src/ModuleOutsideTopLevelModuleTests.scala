package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ModuleOutsideTopLevelModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "Modules, Tasks and Commands can only be defined within a mill Module"
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
