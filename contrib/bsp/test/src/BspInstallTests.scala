package mill.contrib.bsp

import mill.util.ScriptTestSuite
import os._
import utest._

object BspInstallTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "gen-idea-hello-world"
  override def scriptSourcePath: Path = os.pwd / "contrib" / "bsp" / "test" / "resources" / workspaceSlug

  def tests: Tests = Tests {
    "BSP install" - {
      val workspacePath = initWorkspace()
      eval("mill.contrib.BSP/install")

      assert(exists(workspacePath / ".bsp" /"mill.json"))
    }
  }
}
