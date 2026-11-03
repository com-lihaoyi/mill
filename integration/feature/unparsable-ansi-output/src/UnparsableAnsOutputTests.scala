package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object SubprocessStdoutTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res1 = eval("foo.run", mergeErrIntoOut = true, timeout = 20 * 1000).out
      assert(
        res1.contains("coloured then a lone CSI "),
        res1.contains("after")
      )
    }
  }
}
