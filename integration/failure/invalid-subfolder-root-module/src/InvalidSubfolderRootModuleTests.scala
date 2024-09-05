package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object InvalidSubfolderRootModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("object `package` "))
      assert(res.err.contains("must extend `RootModule`"))
    }
  }
}
