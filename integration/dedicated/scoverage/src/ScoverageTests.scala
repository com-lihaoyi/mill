package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object ScoverageTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - retry(3) {
      integrationTest { tester =>
        import tester.*
        assert(eval("__.compile").isSuccess)
        assert(eval("core[2.13.11].scoverage.xmlReport").isSuccess)
      }
    }
  }
}
