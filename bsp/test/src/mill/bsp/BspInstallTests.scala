package mill.bsp

import mill.util.ScriptTestSuite
import utest._

object BspInstallTests extends ScriptTestSuite(false) {
  override def workspaceSlug: String = "bsp-install"
  override def scriptSourcePath: os.Path = os.pwd / "bsp" / "test" / "resources" / workspaceSlug
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
