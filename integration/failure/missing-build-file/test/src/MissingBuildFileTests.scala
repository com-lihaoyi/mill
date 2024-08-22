package mill.integration

import utest._

object MissingBuildFileTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res = evalStdout("resolve", "_")
      assert(!res.isSuccess)
      val s"build.sc file not found in $msg. Are you in a Mill project folder?" = res.err
    }
  }
}
