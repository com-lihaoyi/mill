package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object NoModulesInHelperFileTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester.*
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(
        res.err.contains(
          "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"
        )
      )
      assert(res.err.contains("object foo extends Module"))
    }
  }
}
