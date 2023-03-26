package mill.integration
package local
import mill.bsp.Constants
import utest._

object BspInstallDebugTests extends IntegrationTestSuite("bsp-install", false) {
  // we purposely enable debugging in this simulated test env
  override val debugLog: Boolean = true

  def tests: Tests = Tests {
    test("BSP install forwards --debug option to server") {
      val workspacePath = initWorkspace()
      eval("mill.bsp.BSP/install") ==> true
      val jsonFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
      os.exists(jsonFile) ==> true
      os.read(jsonFile).contains("--debug") ==> true
    }
  }
}
