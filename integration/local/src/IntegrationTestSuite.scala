package mill.integration

import mill.util.ScriptTestSuite
import utest._

abstract class IntegrationTestSuite(
    override val workspaceSlug: String,
    fork: Boolean,
    clientServer: Boolean = false
) extends ScriptTestSuite(fork, clientServer) {

  override def workspacePath: os.Path =
    os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / workspaceSlug

  override def scriptSourcePath: os.Path =
    os.pwd / "integration" / "local" / "resources" / workspaceSlug

}
