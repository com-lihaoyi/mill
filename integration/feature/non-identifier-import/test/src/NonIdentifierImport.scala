package mill.integration

import utest._

object NonIdentifierImport extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test("test") - {
      assert(eval("foo-bar-module.compile"))
    }
  }
}
