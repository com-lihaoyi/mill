package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*
trait InitBuildTestSuite extends UtestIntegrationTestSuite {
  def gitUrl: String
  def gitRev: String
  def initArgs: Seq[String]

  lazy val tester: IntegrationTester = new IntegrationTester(
    daemonMode,
    workspaceSourcePath,
    millExecutable,
    debugLog,
    cleanupProcessIdFile = cleanupProcessIdFile
  ) {
    override def initWorkspace() = {
      os.makeDir(workspacePath)
      os.proc("git", "init", ".").call(workspacePath, stderr = os.Pipe)
      os.proc("git", "remote", "add", "-f", "origin", gitUrl).call(workspacePath, stderr = os.Pipe)
      os.proc("git", "checkout", gitRev).call(workspacePath, stderr = os.Pipe)
      eval("init" +: initArgs)
    }
  }

  def checkFails(task: os.Shellable, logMessages: String*): Unit =
    checkTask(task, false, logMessages)

  def checkPasses(task: os.Shellable, logMessages: String*): Unit =
    checkTask(task, true, logMessages)

  def checkTask(task: os.Shellable, isSuccess: Boolean, logMessages: Seq[String]): Unit = {
    val result = tester.eval(task, mergeErrIntoOut = true)
    assert(result.isSuccess == isSuccess)
    if (logMessages.nonEmpty) {
      val log = result.out
      val messages = logMessages.filter(log.contains)
      assert(messages == logMessages)
    }
  }

  override def utestAfterAll() = tester.close()
}
