package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object BuildFileInSubfolderTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = tester.eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("Mill build.mill files can only be in the project root"))
    }
  }
}
