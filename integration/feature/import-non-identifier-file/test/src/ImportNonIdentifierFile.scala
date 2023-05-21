package mill.integration

import utest._

object ImportNonIdentifierFile extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test("test") - {
      assert(eval("foo-bar-module.compile"))
    }
  }
}
