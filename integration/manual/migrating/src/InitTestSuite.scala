package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
abstract class InitTestSuite(
    gitUrl: String,
    gitRev: String,
    initArgs: Seq[String],
    gitBranch: Boolean = true
) extends UtestIntegrationTestSuite {

  lazy val tester = new IntegrationTester(
    daemonMode,
    workspaceSourcePath,
    millExecutable,
    debugLog,
    cleanupProcessIdFile = cleanupProcessIdFile
  ) {
    override def initWorkspace() = {
      os.makeDir(workspacePath)
      if (gitBranch) {
        os.proc("git", "clone", gitUrl, "--depth", "1", "--branch", gitRev, workspacePath).call()
      } else {
        os.proc("git", "init", workspacePath).call()
        os.proc("git", "remote", "add", "-f", "origin", gitUrl).call(workspacePath)
        os.proc("git", "checkout", gitRev).call(workspacePath)
      }
      this.eval("init" +: initArgs, stdout = os.Inherit, stderr = os.Inherit)
      this.eval(("--meta-level", 1, "__.compile"), stdout = os.Inherit, stderr = os.Inherit)
      os.symlink(workspacePath / millExecutable.last, millExecutable)
    }
  }

  override def utestAfterAll() = tester.close()

  def eval(cmd: os.Shellable): IntegrationTester.EvalResult =
    tester.eval(cmd, stdout = os.Inherit, stderr = os.Inherit)
}
