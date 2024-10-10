package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object SubfolderHelperModuleCollisionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      // Not a great error message but it will have to do for now
      assert(res.err.contains("Trying to define package with same name as class sub"))
    }
  }
}
