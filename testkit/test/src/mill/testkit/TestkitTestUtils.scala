package mill.testkit

import mill.constants.DaemonFiles
object TestkitTestUtils {
  def getProcessIdFiles(workspacePath: os.Path) = {
    os.walk(workspacePath / "out")
      .filter(_.last == DaemonFiles.processId)
  }
}
