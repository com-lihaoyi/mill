package mill.integration

import utest._

import scala.util.matching.Regex

object PublishModuleTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("foo.publishLocal")

      if (integrationTestMode != "local") {
        assert(res.err.contains("Publishing Artifact"))
      }
    }
  }
}
