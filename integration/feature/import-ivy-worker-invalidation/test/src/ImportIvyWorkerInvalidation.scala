package mill.integration

import utest._

object ImportIvyWorkerInvalidation extends IntegrationTestSuite {

  val tests: Tests = Tests {
    test {
      val wsRoot = initWorkspace()
      assert(eval("app.compile"))
      mangleFile(wsRoot / "build.sc", _.replace("object app", "println(\"hello\"); object app"))
      assert(eval("app.compile"))
    }
  }
}
