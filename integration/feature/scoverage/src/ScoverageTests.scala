package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ScoverageTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      assert(eval("__.compile").isSuccess)
      assert(eval("core[2.13.11].scoverage.xmlReport").isSuccess)
    }
  }
}
