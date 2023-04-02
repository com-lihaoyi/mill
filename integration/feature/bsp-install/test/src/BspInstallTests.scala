package mill.integration
package local
import mill.bsp.Constants
import utest._

object BspInstallTests extends IntegrationTestSuite {
  val bsp4jVersion = sys.props.getOrElse("BSP4J_VERSION", ???)

  def tests: Tests = Tests {
    test("BSP install") {
      val workspacePath = initWorkspace()
      assert(eval("mill.bsp.BSP/install"))
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
