package mill.integration
package local
import mill.bsp.Constants
import utest._

object BspInstallTests extends IntegrationTestSuite("bsp-install", false) {
  def tests: Tests = Tests {
    test("BSP install") {
      val workspacePath = initWorkspace()
      eval("mill.bsp.BSP/install") ==> true
      val jsonFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
      os.exists(jsonFile) ==> true
      os.read(jsonFile).contains("--debug") ==> false
    }
  }
}
