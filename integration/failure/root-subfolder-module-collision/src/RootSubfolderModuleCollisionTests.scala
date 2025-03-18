package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object RootSubfolderModuleCollisionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("sub is already defined as package build.sub in package build"))
    }
  }
}
