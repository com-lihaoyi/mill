package mill.integration
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import mill.util.Jvm
import utest.{assert, assertGoldenFile, assertGoldenLiteral}
import java.io.File.pathSeparator
trait MillInitImportTestSuite extends UtestIntegrationTestSuite {
  override def propagateJavaHome = false

  def checkImport(
      gitUrl: String,
      gitBranch: String,
      initArgs: Seq[String] = Nil,
      configsGoldenFile: os.SubPath = null,
      passingTasks: Seq[os.Shellable] = Nil,
      failingTasks: Seq[os.Shellable] = Nil,
      envJvmId: String = "zulu:11"
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
      override def millTestSuiteEnv = if (this.propagateJavaHome) super.millTestSuiteEnv
      else {
        val javaHome = Jvm.resolveJavaHome(envJvmId).get
        val javaExe = Jvm.javaExe(Some(javaHome))
        Map(
          "JAVA_HOME" -> javaHome.toString,
          "PATH" -> s"$javaExe$pathSeparator${System.getenv("PATH")}"
        )
      }
    }
    try {
      import tester.{eval, workspaceSourcePath as resources}
      val initRes = eval("init" +: initArgs)
      assert(initRes.isSuccess)

      if (configsGoldenFile != null) {
        val taskNames = Seq(
          "repositories",
          "jvmId",
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
          "errorProneVersion",
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
        val showModuleDepsOut = eval("__.showModuleDeps").out
        val actualConfigs = taskNames
          .map(task => eval(("show", s"__.$task")).out)
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
