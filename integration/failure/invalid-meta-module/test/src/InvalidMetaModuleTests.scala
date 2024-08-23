package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object InvalidMetaModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains(
        "Root module in mill-build/build.sc must be of class mill.runner.MillBuildRootModule"
      ))
    }
  }
}
