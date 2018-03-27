package mill.integration

import ammonite.ops._
import mill.util.ScriptTestSuite
import utest._

abstract class IntegrationTestSuite(repoKey: String, val workspaceSlug: String, fork: Boolean)
  extends ScriptTestSuite(fork){
  val buildFilePath = pwd / 'integration / 'test / 'resources / workspaceSlug
  def scriptSourcePath = {
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    val path = sys.props(repoKey)
    val Seq(wrapper) = ls(Path(path))
    wrapper
  }

  def buildFiles: Seq[Path] = {
    Seq(buildFilePath / "build.sc")
  }

  override def initWorkspace() = {
    super.initWorkspace()
    buildFiles.foreach { file =>
      cp.over(file, workspacePath / file.name)
    }
    assert(!ls.rec(workspacePath).exists(_.ext == "class"))
  }
}
