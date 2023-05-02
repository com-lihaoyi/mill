package mill.integration

import utest._

object ImportRepoTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test("test") - {
      assert(eval("foo-bar.compile"))
    }
  }
}
