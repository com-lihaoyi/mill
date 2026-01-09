package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.{assert, assertGoldenFile, assertGoldenLiteral}
trait MillInitImportTestSuite extends UtestIntegrationTestSuite {
  def checkImport(
      gitUrl: String,
      gitBranch: String,
      initArgs: Seq[String] = Nil,
      configsGoldenFile: os.SubPath = null,
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
      import tester.{eval, workspaceSourcePath as resources}
      val initRes = eval("init" +: initArgs)
      assert(initRes.isSuccess)

      if (configsGoldenFile != null) {
        // Tasks that may not exist on all modules (e.g., errorProneDeps only on ErrorProneModule)
        val expectedFailureTasks = Set(
          "errorProneDeps",
          "errorProneOptions",
          "scalaVersion",
          "scalacOptions",
          "scalacPluginMvnDeps",
          "scalaJSVersion",
          "moduleKind",
          "scalaNativeVersion"
        )
        val taskNames = Seq(
          "repositories",
          "mandatoryMvnDeps",
          "mvnDeps",
          "compileMvnDeps",
          "runMvnDeps",
          "bomMvnDeps",
          "javacOptions",
          "pomParentProject",
          "pomSettings",
          "publishVersion",
          "versionScheme",
          "publishProperties",
          "errorProneDeps",
          "errorProneOptions",
          "scalaVersion",
          "scalacOptions",
          "scalacPluginMvnDeps",
          "scalaJSVersion",
          "moduleKind",
          "scalaNativeVersion",
          "testParallelism",
          "testSandboxWorkingDir"
        )
        def evalTask(task: String): String = {
          val result = eval(("show", s"__.$task"))
          if (!result.isSuccess && !expectedFailureTasks.contains(task)) {
            throw new Exception(s"Command failed: show __.$task\n${result.debugString}")
          }
          result.out
        }
        val showModuleDepsResult = eval("__.showModuleDeps")
        if (!showModuleDepsResult.isSuccess) {
          throw new Exception(
            s"Command failed: __.showModuleDeps\n${showModuleDepsResult.debugString}"
          )
        }
        val showModuleDepsOut = showModuleDepsResult.out
        val actualConfigs = taskNames
          .map(evalTask)
          .mkString(
            s"""$showModuleDepsOut
               |""".stripMargin,
            s"""
               |""".stripMargin,
            ""
          )
        val configsFile = resources / configsGoldenFile
        assertGoldenFile(actualConfigs, configsFile.wrapped)
      }

      val passingTasks0 = passingTasks.filter(eval(_).isSuccess)
      assertGoldenLiteral(passingTasks0, passingTasks)
      val failingTasks0 = failingTasks.filterNot(eval(_).isSuccess)
      assertGoldenLiteral(failingTasks0, failingTasks)
    } finally tester.close()
  }
}
