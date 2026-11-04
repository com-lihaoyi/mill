package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

// With the ticker enabled, Mill strips non-colour escape sequences from each line of
// output it relays from a forked test process. A line containing an escape sequence
// that cannot be parsed must not stop the rest of the output from being relayed.
object FullRunLogsUnparseableEscapeTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("unparseableEscape") - integrationTest { tester =>
      import tester.*

      val res = eval(("-i", "--ticker", "true", "unparseableEscape.test"), propagateEnv = false)

      assert(res.isSuccess)
      assert(res.out.linesIterator.exists(_.endsWith("after")))
      assert(!res.err.contains("IllegalStateException"))
    }
  }
}
