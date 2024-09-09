package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object SubfolderMissingBuildPrefix extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("object y is not a member of package build_.sub"))
    }
  }
}
