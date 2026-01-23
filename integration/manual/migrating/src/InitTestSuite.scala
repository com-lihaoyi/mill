package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
abstract class InitTestSuite(gitUrl: String, gitBranch: String, initArgs: Seq[String])
    extends UtestIntegrationTestSuite {

  lazy val tester = new IntegrationTester(
    daemonMode,
    workspaceSourcePath,
    millExecutable,
    debugLog,
    cleanupProcessIdFile = cleanupProcessIdFile
  ) {
    override def initWorkspace() = {
      os.makeDir(workspacePath)
      os.proc("git", "clone", gitUrl, "--depth", "1", "--branch", gitBranch, workspacePath).call()
      this.eval("init" +: initArgs, stdout = os.Inherit, stderr = os.Inherit)
      this.eval(("--meta-level", 1, "__.compile"), stdout = os.Inherit, stderr = os.Inherit)
      os.symlink(workspacePath / millExecutable.last, millExecutable)
    }
  }

  override def utestAfterAll() = tester.close()

  def eval(cmd: os.Shellable): IntegrationTester.EvalResult =
    tester.eval(cmd, stdout = os.Inherit, stderr = os.Inherit)
}
