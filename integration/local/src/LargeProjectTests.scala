package mill.integration

import mill.util.ScriptTestSuite
import utest._

class LargeProjectTests(fork: Boolean) extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "large-project"
  override def workspacePath: os.Path = os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / getClass().getName()
  def scriptSourcePath: os.Path = os.pwd / "integration" / "local" / "resources" / workspaceSlug

  val tests = Tests {
    initWorkspace()
    "test" - {

      assert(eval("foo.common.one.compile"))
    }

  }
}
