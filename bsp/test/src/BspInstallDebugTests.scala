package mill.bsp

import mill.util.ScriptTestSuite
import utest._

object BspInstallDebugTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "bsp-install"
  override def scriptSourcePath: os.Path = os.pwd / "bsp" / "test" / "resources" / workspaceSlug

  // we purposely enable debugging in this simulated test env
  override val debugLog: Boolean = true

  def tests: Tests = Tests {
    test("BSP install forwards --debug option to server") {
      val workspacePath = initWorkspace()
      eval("mill.bsp.BSP/install") ==> true
      val jsonFile = workspacePath / ".bsp" / s"${BSP.serverName}.json"
      os.exists(jsonFile) ==> true
      os.read(jsonFile).contains("--debug") ==> true
    }
  }
}
