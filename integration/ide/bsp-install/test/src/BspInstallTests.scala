package mill.integration

import mill.testkit.IntegrationTestSuite
import mill.bsp.Constants
import utest._

object BspInstallTests extends IntegrationTestSuite {
  val bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

  def tests: Tests = Tests {
    test("BSP install") {
      initWorkspace()
      assert(eval("mill.bsp.BSP/install").isSuccess)
      val jsonFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
      assert(os.exists(jsonFile))
      val contents = os.read(jsonFile)
      assert(
        !contents.contains("--debug"),
        contents.contains(s""""bspVersion":"${bsp4jVersion}"""")
      )
    }
  }
}
