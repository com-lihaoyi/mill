package mill.integration

import mill.util.ScriptTestSuite
import utest._

abstract class IntegrationTestSuite(repoKey: String, val workspaceSlug: String, fork: Boolean)
  extends ScriptTestSuite(fork){
  val buildFilePath = os.pwd / 'integration / 'test / 'resources / workspaceSlug
  def scriptSourcePath = {
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    val path = sys.props(repoKey)
    val Seq(wrapper) = os.list(os.Path(path))
    wrapper
  }

  def buildFiles: Seq[os.Path] = os.walk(buildFilePath)

  override def initWorkspace() = {
    super.initWorkspace()
    buildFiles.foreach { file =>
      os.copy.over(file, workspacePath / file.last)
    }
    assert(!os.walk(workspacePath).exists(_.ext == "class"))
  }
}
