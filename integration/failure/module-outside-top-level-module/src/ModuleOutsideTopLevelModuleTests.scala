package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object ModuleOutsideTopLevelModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester.*
      val res = eval(("resolve", "_"))
      assert(!res.isSuccess)
      res.assertContainsLines(
        "[error] build.mill:4:8",
        "object invalidModule extends Module",
        "^"
      )
      assert(
        res.err.contains(
          "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"
        )
      )
    }
  }
}
