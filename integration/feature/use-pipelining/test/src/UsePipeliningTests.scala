package mill.integration

import utest._

object UsePipeliningTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    "usePipelining does compile" - {
      val successful = eval("mod2.compile")
      assert(successful)
    }
  }
}
