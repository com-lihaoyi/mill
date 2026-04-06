package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object HeaderMvnDepsWorkerInvalidation extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      assert(eval("app.compile").isSuccess)
      modifyFile(
        workspacePath / "build.mill",
        _.replace("object app", "println(\"hello\"); object app")
      )
      assert(eval("app.compile").isSuccess)
    }
  }
}
