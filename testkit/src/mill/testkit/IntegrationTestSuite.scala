package mill.testkit

import os.Path

trait IntegrationTestSuite {
  protected def workspaceSourcePath: os.Path
  protected def clientServerMode: Boolean

  protected def millExecutable: Path
  protected def propagateJavaHome: Boolean = true

  def debugLog: Boolean = false
  def integrationTest[T](t: IntegrationTester => T): T = {
    val tester = new IntegrationTester(
      clientServerMode,
      workspaceSourcePath,
      millExecutable,
      debugLog,
      baseWorkspacePath = os.pwd,
      propagateJavaHome = propagateJavaHome
    )
    try t(tester)
    finally tester.close()
  }
}
