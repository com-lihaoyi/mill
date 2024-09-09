package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object MisnamedRootModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(res.err.contains(
        "Only one RootModule named `package` can be defined in a build, not: foo"
      ))
    }
  }
}
