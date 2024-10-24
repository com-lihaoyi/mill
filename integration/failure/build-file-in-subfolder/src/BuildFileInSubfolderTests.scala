package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object BuildFileInSubfolderTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("Mill build.mill files can only be in the project root"))
    }
  }
}
