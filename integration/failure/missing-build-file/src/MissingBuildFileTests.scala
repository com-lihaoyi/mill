package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object MissingBuildFileTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(!res.isSuccess)
      val s"${prefix}build.mill file not found in $msg. Are you in a Mill project folder?" = res.err
    }
  }
}
