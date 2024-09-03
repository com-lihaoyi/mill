package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object MisnamedRootModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(res.err.contains(
        "Only one RootModule named `package` can be defined in a build, not: foo"
      ))
    }
  }
}
