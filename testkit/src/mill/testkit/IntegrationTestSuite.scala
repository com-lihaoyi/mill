package mill.testkit

import os.Path

trait IntegrationTestSuite {
  protected def workspaceSourcePath: os.Path
  protected def clientServerMode: Boolean

  protected def millExecutable: Path

  def debugLog: Boolean = false
  def integrationTest[T](t: IntegrationTester => T): T = {
    val tester = new IntegrationTester(
      clientServerMode,
      workspaceSourcePath,
      millExecutable,
      debugLog,
      baseWorkspacePath = os.pwd
    )
    try t(tester)
    finally tester.close()
  }
}
