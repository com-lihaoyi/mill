package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object HeaderMvnDepsWorkerInvalidation extends UtestIntegrationTestSuite {

  // This test deliberately replaces a build classloader while retaining a worker from
  // the previous build. Keep it isolated from the shared in-memory launcher used by the
  // rest of the integration suite so their classloader state cannot interfere.
  override def allowSharedOutputDir: Boolean = false

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
