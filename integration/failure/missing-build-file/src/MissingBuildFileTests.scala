package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object MissingBuildFileTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(!res.isSuccess)

      val s"${prefix}No build file (build.mill) found in $msg. Are you in a Mill project directory?" =
        res.err: @unchecked
      // Silence unused variable warning
      val _ = prefix
      val _ = msg
    }
  }
}
