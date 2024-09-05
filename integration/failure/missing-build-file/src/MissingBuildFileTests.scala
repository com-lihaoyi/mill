package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object MissingBuildFileTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester => import tester._
      val res = tester.eval(("resolve", "_"))
      assert(!res.isSuccess)
      val s"build.mill file not found in $msg. Are you in a Mill project folder?" = res.err
    }
  }
}
