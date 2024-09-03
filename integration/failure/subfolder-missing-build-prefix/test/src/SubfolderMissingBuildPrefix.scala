package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object SubfolderMissingBuildPrefix extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("object y is not a member of package build_.sub"))
    }
  }
}
