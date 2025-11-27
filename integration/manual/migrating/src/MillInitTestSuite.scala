package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.assertGoldenLiteral
trait MillInitTestSuite extends UtestIntegrationTestSuite {
  def checkImport(
      gitUrl: String,
      gitBranch: String,
      initArgs: Seq[String] = Nil,
      passingTasks: Seq[os.Shellable] = Nil,
      failingTasks: Seq[os.Shellable] = Nil
  ): Unit = {
    val tester = new IntegrationTester(
      daemonMode,
      workspaceSourcePath,
      millExecutable,
      debugLog,
      baseWorkspacePath = os.pwd,
      propagateJavaHome = propagateJavaHome
    ) {
      override val workspacePath = {
        val cwd = os.temp.dir(dir = baseWorkspacePath, deleteOnExit = false)
        // Clone into a new directory to preserve repo dir name.
        os.proc("git", "clone", gitUrl, "--depth", 1, "--branch", gitBranch)
          .call(cwd = cwd)
        os.list(cwd).head
      }
      override def initWorkspace() = {}
    }
    try {
      val initRes = tester.eval("init" +: initArgs, stdout = os.Inherit, stderr = os.Inherit)
      if (initRes.isSuccess) {
        val passingTasks0 = passingTasks.filter {
          tester.eval(_, stdout = os.Inherit, stderr = os.Inherit).isSuccess
        }
        assertGoldenLiteral(passingTasks0, passingTasks)
        val failingTasks0 = failingTasks.filterNot {
          tester.eval(_, stdout = os.Inherit, stderr = os.Inherit).isSuccess
        }
        assertGoldenLiteral(failingTasks0, failingTasks)
      }
    } finally tester.close()
  }
}
