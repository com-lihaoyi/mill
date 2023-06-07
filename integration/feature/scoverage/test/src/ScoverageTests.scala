package mill.integration

import utest._

object ScoverageTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test("test") - {
      assert(eval("__.compile"))
    }
  }
}
