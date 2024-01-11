package mill.integration

import utest._

object ScoverageTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    test("test") - {
      assert(eval("__.compile"))
      assert(eval("core[2.13.11].scoverage.xmlReport"))
    }
  }
}
