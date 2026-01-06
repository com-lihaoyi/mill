package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object RootSubfolderModuleCollisionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester.*
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("Reference to sub is ambiguous."))
      assert(res.err.contains("It is both defined in class package_"))
      assert(res.err.contains("and inherited subsequently in class package_"))
    }
  }
}
