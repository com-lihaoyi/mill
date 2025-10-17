package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScoverageTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - retry(3) {
      integrationTest { tester =>
        import tester._

        prepEval("__.compile").runWithClues(r => assert(r.isSuccess))
        prepEval("core[2.13.11].scoverage.xmlReport").runWithClues(r => assert(r.isSuccess))
      }
    }
  }
}
