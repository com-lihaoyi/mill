package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object NoModulesInHelperFileTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester.*
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] helper.mill:3:8",
        "object foo extends Module",
        "       ^"
      )
      assert(
        res.err.contains(
          "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"
        )
      )
    }
  }
}
