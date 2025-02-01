package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object SubfolderMissingBuildPrefix extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(res.err.contains("value y is not a member of build_.sub"))
    }
  }
}
