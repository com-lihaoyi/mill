package mill.integration

import utest._

import scala.util.matching.Regex

object MultipleTopLevelModulesTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success") {
      val res = evalStdout("resolve", "_")
      assert(!res.isSuccess)
      assert(res.err.contains(
        "Only one RootModule can be defined in a build, not 2: millbuild.build$bar$,millbuild.build$foo$"
      ))
    }
  }
}
