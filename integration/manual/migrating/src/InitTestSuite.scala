package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
trait InitTestSuite extends UtestIntegrationTestSuite {
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
      os.proc("git", "init", workspacePath).call()
      os.proc("git", "remote", "add", "-f", "origin", gitUrl).call(workspacePath)
      os.proc("git", "checkout", gitRev).call(workspacePath)
      this.eval("init" +: initArgs, stdout = os.Inherit, stderr = os.Inherit)
      this.eval(("--meta-level", 1, "__.compile"), stdout = os.Inherit, stderr = os.Inherit)
      // os.symlink(tester.workspacePath / tester.millExecutable.last, tester.millExecutable)
    }
  }

  override def utestAfterAll() = tester.close()

  def eval(cmd: os.Shellable): IntegrationTester.EvalResult =
    tester.eval(cmd, stdout = os.Inherit, stderr = os.Inherit)
}
