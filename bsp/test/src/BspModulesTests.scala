package mill.bsp

import mill.util.ScriptTestSuite
import utest._

object BspModulesTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "bsp-modules"
  override def scriptSourcePath: os.Path = os.pwd / "bsp" / "test" / "resources" / workspaceSlug

  def tests: Tests = Tests {
    test("BSP module with foreign modules") {
      test("can be installed") {
        val workspacePath = initWorkspace()
        eval("mill.bsp.BSP/install") ==> true
        os.exists(workspacePath / Constants.bspDir / s"${Constants.serverName}.json") ==> true
      }
      test("ModuleUtils resolves all referenced transitive modules") {
        val workspacePath = initWorkspace()
        eval("validate") ==> true
        val file = workspacePath / "out" / "validate.dest" / "transitive-modules.json"
        os.exists(file) ==> true
        val readModules = os.read.lines(file).sorted
        val expectedModules = Seq(
          "", // the root module has no segemnts at all
          "HelloBsp",
          "HelloBsp.test",
          "foreign-modules.proj1.build.proj1",
          "foreign-modules.proj2.build.proj2"
          // "foreign-modules.proj3.proj3" // still not detected
        ).sorted
        readModules ==> expectedModules
      }
    }
  }
}
