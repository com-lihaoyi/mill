package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object ZincBuildCompilationTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester.*

      eval(("dummy"))

      assert(timing(1)("compile").cached == false)

      eval(("dummy"))
      assert(timing(1).get("compile") == None || timing(1)("compile").cached == true)

      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      eval(("dummy"))
      assert(timing(1)("compile").cached == false)

      eval(("dummy"))
      assert(timing(1).get("compile") == None || timing(1)("compile").cached == true)

      val subFolderResCached = eval(("dummy"))
      assert(!subFolderResCached.err.contains("compiling"))

      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("running helperFoo", "running helperFoo2")
      )
      eval(("dummy"))
      assert(timing(1)("compile").cached == false)

    }
  }
}
