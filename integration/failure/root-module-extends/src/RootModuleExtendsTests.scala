package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object RootModuleExtendsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(
        res.err.contains("object `package` in build.mill must extend a subclass of `mill.Module`")
      )
    }
  }
}
