package mill.integration

import utest._

object LargeProjectTests extends IntegrationTestSuite.Cross {
  val tests = Tests {
    initWorkspace()
    "test" - {

      assert(eval("foo.common.one.compile"))
    }
  }
}
