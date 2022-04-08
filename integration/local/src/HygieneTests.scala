package mill.integration

import mill.util.ScriptTestSuite
import utest._

class HygieneTests(fork: Boolean) extends ScriptTestSuite(fork) {
  override def workspaceSlug: String = "hygiene"
  override def workspacePath: os.Path = os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / getClass().getName()
  override def scriptSourcePath: os.Path = os.pwd / "integration" / "local" / "resources" / workspaceSlug

  val tests = Tests {
    initWorkspace()

    test {
      assert(eval("scala.foo"))
      val output = meta("scala.foo")
      assert(output.contains("\"fooValue\""))
    }

  }
}
