package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import mill.util.Jvm
import utest.{assert, assertGoldenFile, assertGoldenLiteral}
trait MillInitImportTestSuite extends UtestIntegrationTestSuite {
  def checkImport(
      gitUrl: String,
      gitBranch: String,
      initArgs: Seq[String] = Nil,
      configsGoldenFile: os.SubPath = null,
      passingTasks: Seq[os.Shellable] = Nil,
      failingTasks: Seq[os.Shellable] = Nil,
      systemJvmId: String = "zulu:21"
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
      val javaHome = Jvm.resolveJavaHome(systemJvmId).get
      val env = Map("JAVA_HOME" -> javaHome.toString)
      val initRes = eval("init" +: initArgs, env)
      assert(initRes.isSuccess)

      if (configsGoldenFile != null) {
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
        val showModuleDepsOut = eval("__.showModuleDeps", env).out
        val actualConfigs = taskNames
          .map(task => eval(("show", s"__.$task"), env).out)
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

      val passingTasks0 = passingTasks.filter(eval(_, env).isSuccess)
      assertGoldenLiteral(passingTasks0, passingTasks)
      val failingTasks0 = failingTasks.filterNot(eval(_, env).isSuccess)
      assertGoldenLiteral(failingTasks0, failingTasks)
    } finally tester.close()
  }
}
