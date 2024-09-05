package mill.testkit

abstract class IntegrationTestSuite extends IntegrationTestSuiteBase {
  def debugLog: Boolean = false
  def integrationTest[T](t: IntegrationTester => T) = {
    val tester =
      new IntegrationTester(clientServerMode, workspaceSourcePath, millExecutable, debugLog)
    try t(tester)
    finally tester.close()
  }
}
