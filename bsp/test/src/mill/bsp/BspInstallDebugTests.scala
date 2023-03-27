package mill.bsp

import mill.util.ScriptTestSuite
import utest._

object BspInstallDebugTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "bsp-install"
  override def scriptSourcePath: os.Path = os.pwd / "bsp" / "test" / "resources" / workspaceSlug
  val bsp4jVersion = sys.props.getOrElse("BSP4J_VERSION", ???)

  // we purposely enable debugging in this simulated test env
  override val debugLog: Boolean = true

  def tests: Tests = Tests {
    test("BSP install forwards --debug option to server") {
      val workspacePath = initWorkspace()
      eval("mill.bsp.BSP/install") ==> true
      val jsonFile = workspacePath / Constants.bspDir / s"${Constants.serverName}.json"
      assert(os.exists(jsonFile))
      val contents = os.read(jsonFile)
      assert(
        contents.contains("--debug"),
        contents.contains(s""""bspVersion":"${bsp4jVersion}"""")
      )
    }
  }
}
