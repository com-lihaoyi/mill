package mill.integration

import utest._

object MissingBuildFileTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res = evalStdout("resolve", "_")
      assert(!res.isSuccess)
      assert(res.err.contains("build.sc file not found. Are you in a Mill project folder"))
    }
  }
}
