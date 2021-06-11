package mill.bsp

import mill.util.ScriptTestSuite
import os._
import utest._

object BspInstallTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "bsp-install"
  override def scriptSourcePath: Path = os.pwd / "bsp" / "test" / "resources" / workspaceSlug

  def tests: Tests = Tests {
    "BSP install" - {
      val workspacePath = initWorkspace()
      eval("mill.bsp.BSP/install")

      assert(exists(workspacePath / ".bsp" / "mill.json"))
    }
  }
}
