package mill.integration

import utest._

import scala.util.matching.Regex

object InvalidMetaModuleTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success") {
      val res = evalStdout("resolve", "_")
      assert(res.isSuccess == false)
      assert(res.err.contains(
        "Root module in mill-build/build.sc must be of class mill.runner.MillBuildRootModule"
      ))
    }
  }
}
