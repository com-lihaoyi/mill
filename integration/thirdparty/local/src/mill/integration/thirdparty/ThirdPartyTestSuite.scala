package mill.integration.thirdparty
import mill.integration.IntegrationTestSuite
import utest._

abstract class ThirdPartyTestSuite(repoKey: String, workspaceSlug: String, fork: Boolean)
    extends IntegrationTestSuite(workspaceSlug, fork) {
  val buildFilePath = os.pwd / "integration" / "thirdparty" / "local" / "resources" / workspaceSlug
  override def workspacePath: os.Path =
    os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / workspaceSlug

  override def scriptSourcePath = {
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    val path = sys.props.getOrElse(repoKey, ???)
    val Seq(wrapper) = os.list(os.Path(path))
    wrapper
  }

  /** Files to copy into the workspace */
  def buildFiles: Seq[os.Path] = os.walk(buildFilePath)

  override def initWorkspace() = {
    val path = super.initWorkspace()
    buildFiles.foreach { file =>
      os.copy.over(file, workspacePath / file.last)
    }
    assert(!os.walk(workspacePath).exists(_.ext == "class"))
    path
  }
}
