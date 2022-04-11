package mill.integration

import mill.util.ScriptTestSuite
import utest._

class LargeProjectTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("large-project", fork, clientServer) {
  val tests = Tests {
    initWorkspace()
    "test" - {

      assert(eval("foo.common.one.compile"))
    }
  }
}
