package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object SimpleYamlErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("run")

      assert(!res.isSuccess)

      assert(res.err.contains("scalaVersion Not Implemented"))
      assert(res.err.contains("""method scalaVersionWrong overrides nothing"""))

    }
  }
}
