package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object MisnamedRootModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester => import tester._
      val res = tester.eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(res.err.contains(
        "Only one RootModule named `package` can be defined in a build, not: foo"
      ))
    }
  }
}
