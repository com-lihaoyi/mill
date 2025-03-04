package mill.testkit
import mill.constants.ServerFiles
object TestkitTestUtils {
  def getProcessIdFiles(workspacePath: os.Path) = {
    os.walk(workspacePath / "out")
      .filter(_.last == ServerFiles.processId)
  }
}
