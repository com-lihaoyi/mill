package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ImportIvyWorkerInvalidation extends IntegrationTestSuite {

  val tests: Tests = Tests {
    test {
      initWorkspace()
      assert(eval("app.compile").isSuccess)
      modifyFile(
        workspacePath / "build.mill",
        _.replace("object app", "println(\"hello\"); object app")
      )
      assert(eval("app.compile").isSuccess)
    }
  }
}
