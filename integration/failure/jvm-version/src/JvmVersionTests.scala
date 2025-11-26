package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object JvmVersionTests extends UtestIntegrationTestSuite {
  def captureOutErr = true
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("version")
      assert(!res.isSuccess)
      assert(
        res.err.contains(
        """Invalid java.version 11.0.29. Mill requires Java 17 and above to run the build tool itself. """+
        """Individual `JavaModule` can be set to lower Java versions via `def jvmId = "11"`"""
        )
      )
    }
  }
}
