package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.{assert, assertGoldenFile}
trait MillInitImportTestSuite extends UtestIntegrationTestSuite {
  def checkImport(
      repoName: String,
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
        val destPath = cwd / repoName
        os.copy(os.Path(sys.env(s"MILL_INIT_REPO_$repoName")), destPath)
        destPath
      }

      override def initWorkspace() = {}
    }
    try {
      import tester.{eval, workspaceSourcePath as resources}
      val initRes = eval("init" +: initArgs)
      assert(initRes.isSuccess)

      for {
        taskExpectedOut <- os.list(resources / repoName)
        task = taskExpectedOut.baseName.split(" ")
        taskResult = eval(task)
      } assertGoldenFile(taskResult.out, taskExpectedOut.toNIO)

      for (task <- passingTasks) assert(eval(task).isSuccess)
      for (task <- failingTasks) assert(!eval(task).isSuccess)

    } finally tester.close()
  }
}
