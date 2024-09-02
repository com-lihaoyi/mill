package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object InvalidRootModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("object `package` in "))
      assert(res.err.contains("must extend `RootModule`"))
    }
  }
}
