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
        val repositories = eval(("show", "__.repositories")).out
        val jvmId = eval(("show", "__.jvmId")).out
        val mvnDeps = eval(("show", "__.mvnDeps")).out
        val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
        val runMvnDeps = eval(("show", "__.runMvnDeps")).out
        val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
        val showModuleDeps = eval("__.showModuleDeps").out
        val javacOptions = eval(("show", "__.javacOptions")).out
        val pomParentProject = eval(("show", "__.pomParentProject")).out
        val pomSettings = eval(("show", "__.pomSettings")).out
        val publishVersion = eval(("show", "__.publishVersion")).out
        val versionScheme = eval(("show", "__.versionScheme")).out
        val publishProperties = eval(("show", "__.publishProperties")).out
        val errorProneVersion = eval(("show", "__.errorProneVersion")).out
        val errorProneDeps = eval(("show", "__.errorProneDeps")).out
        val errorProneJavacEnableOptions = eval(("show", "errorProneJavacEnableOptions")).out
        val errorProneOptions = eval(("show", "__.errorProneOptions")).out
        val scalaVersion = eval(("show", "__.scalaVersion")).out
        val scalacOptions = eval(("show", "__.scalacOptions")).out
        val scalacPluginMvnDeps = eval(("show", "__.scalacPluginMvnDeps")).out
        val scalaJSVersion = eval(("show", "__.scalaJSVersion")).out
        val moduleKind = eval(("show", "__.moduleKind")).out
        val scalaNativeVersion = eval(("show", "__.scalaNativeVersion")).out
        val testParallelism = eval(("show", "__.testParallelism")).out
        val testSandboxWorkingDir = eval(("show", "__.testSandboxWorkingDir")).out
        val actualConfigs = s"""$repositories
                               |$jvmId
                               |$mvnDeps
                               |$compileMvnDeps
                               |$runMvnDeps
                               |$bomMvnDeps
                               |$showModuleDeps
                               |$javacOptions
                               |$pomParentProject
                               |$pomSettings
                               |$publishVersion
                               |$versionScheme
                               |$publishProperties
                               |$errorProneVersion
                               |$errorProneDeps
                               |$errorProneJavacEnableOptions
                               |$errorProneOptions
                               |$scalaVersion
                               |$scalacOptions
                               |$scalacPluginMvnDeps
                               |$scalaJSVersion
                               |$moduleKind
                               |$scalaNativeVersion
                               |$testParallelism
                               |$testSandboxWorkingDir
                               |""".stripMargin
        val configsFile = resources / configsGoldenFile
        assertGoldenFile(actualConfigs, configsFile.wrapped)
      }

      val passingTasks0 = passingTasks.filter(eval(_).isSuccess)
      assertGoldenLiteral(passingTasks0, passingTasks)
      val failingTasks0 = failingTasks.filterNot(eval(_).isSuccess)
      assertGoldenLiteral(failingTasks0, failingTasks)
    } finally tester.close()
  }

  def renderAllImportedConfigurations(tester: IntegrationTester) = {
    import tester.eval
    val repositories = eval(("show", "__.repositories")).out
    val jvmId = eval(("show", "__.jvmId")).out
    val mvnDeps = eval(("show", "__.mvnDeps")).out
    val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
    val runMvnDeps = eval(("show", "__.runMvnDeps")).out
    val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
    val showModuleDeps = eval("__.showModuleDeps").out
    val javacOptions = eval(("show", "__.javacOptions")).out
    val pomParentProject = eval(("show", "__.pomParentProject")).out
    val pomSettings = eval(("show", "__.pomSettings")).out
    val publishVersion = eval(("show", "__.publishVersion")).out
    val versionScheme = eval(("show", "__.versionScheme")).out
    val publishProperties = eval(("show", "__.publishProperties")).out
    val errorProneVersion = eval(("show", "__.errorProneVersion")).out
    val errorProneDeps = eval(("show", "__.errorProneDeps")).out
    val errorProneJavacEnableOptions = eval(("show", "errorProneJavacEnableOptions")).out
    val errorProneOptions = eval(("show", "__.errorProneOptions")).out
    val scalaVersion = eval(("show", "__.scalaVersion")).out
    val scalacOptions = eval(("show", "__.scalacOptions")).out
    val scalacPluginMvnDeps = eval(("show", "__.scalacPluginMvnDeps")).out
    val scalaJSVersion = eval(("show", "__.scalaJSVersion")).out
    val moduleKind = eval(("show", "__.moduleKind")).out
    val scalaNativeVersion = eval(("show", "__.scalaNativeVersion")).out
    val testParallelism = eval(("show", "__.testParallelism")).out
    val testSandboxWorkingDir = eval(("show", "__.testSandboxWorkingDir")).out
    s"""$repositories
       |$jvmId
       |$mvnDeps
       |$compileMvnDeps
       |$runMvnDeps
       |$bomMvnDeps
       |$showModuleDeps
       |$javacOptions
       |$pomParentProject
       |$pomSettings
       |$publishVersion
       |$versionScheme
       |$publishProperties
       |$errorProneVersion
       |$errorProneDeps
       |$errorProneJavacEnableOptions
       |$errorProneOptions
       |$scalaVersion
       |$scalacOptions
       |$scalacPluginMvnDeps
       |$scalaJSVersion
       |$moduleKind
       |$scalaNativeVersion
       |$testParallelism
       |$testSandboxWorkingDir
       |""".stripMargin
  }
}
