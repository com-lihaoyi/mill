package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.bsp.Constants
import utest._

object BspInstallDebugTests extends UtestIntegrationTestSuite {
  override protected def workspaceSourcePath: os.Path =
    super.workspaceSourcePath / "project"

  val bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", Constants.bspProtocolVersion)
  // we purposely enable debugging in this simulated test env
  override val debugLog: Boolean = true

  def tests: Tests = Tests {
    def runTest(bspInstallOption: String) = integrationTest { tester =>
      import tester._
      eval(bspInstallOption).isSuccess ==> true
      val jsonFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
      assert(os.exists(jsonFile))
      val contents = os.read(jsonFile)
      assert(
        contents.contains("--debug"),
        contents.contains(s""""bspVersion":"${bsp4jVersion}"""")
      )
    }
    test("BSP install via external command forwards --debug option to server") {
      runTest("mill.bsp.BSP/install")
    }
    test("BSP install forwards --debug option to server") {
      runTest("--bsp-install")
    }
  }
}
