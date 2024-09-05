package mill.testkit

import os.Path
import utest._

trait IntegrationTestSuite {
  protected def workspaceSourcePath: os.Path
  protected def clientServerMode: Boolean

  protected def millExecutable: Path

  def debugLog: Boolean = false
  def integrationTest[T](t: IntegrationTester => T) = {
    val tester =
      new IntegrationTester(clientServerMode, workspaceSourcePath, millExecutable, debugLog)
    try t(tester)
    finally tester.close()
  }
}
