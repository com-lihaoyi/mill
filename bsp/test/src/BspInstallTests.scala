package mill.bsp

import mill.util.ScriptTestSuite
import utest._

object BspInstallTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "bsp-install"
  override def scriptSourcePath: os.Path = os.pwd / "bsp" / "test" / "resources" / workspaceSlug

  def tests: Tests = Tests {
    test("BSP install") {
      val workspacePath = initWorkspace()
      eval("mill.bsp.BSP/install") ==> true
      val jsonFile = workspacePath / ".bsp" / s"${BSP.serverName}.json"
      os.exists(jsonFile) ==> true
      os.read(jsonFile).contains("--debug") ==> false
    }
  }
}
