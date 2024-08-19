package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object MultipleTopLevelModulesTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = evalStdout("resolve", "_")
      assert(!res.isSuccess)
      assert(res.err.contains(
        "Only one RootModule can be defined in a build, not 2:"
      ))
    }
  }
}
