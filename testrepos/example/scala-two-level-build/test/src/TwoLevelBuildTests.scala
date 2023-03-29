package mill.integration

import utest._

import scala.util.matching.Regex

object TwoLevelBuildTests extends IntegrationTestSuite.Cross {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    def runAssertSuccess() = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (integrationTestMode != "local") assert(res.out.contains("<h1>hello</h1><p>world</p>"))
    }

    test("success"){
      runAssertSuccess()
    }
  }
}
