package mill.integration

import utest._

object LargeProjectTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test("test") - {

      assert(eval("foo.common.one.compile"))
    }
  }
}
