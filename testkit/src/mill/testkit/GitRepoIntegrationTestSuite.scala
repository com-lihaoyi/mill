package mill.testkit

import os.Path

trait GitRepoIntegrationTestSuite extends UtestIntegrationTestSuite {

  def gitRepoUrl: String
  def gitRepoBranch: String
  def gitRepoDepth: Int = 1

  override def integrationTest[T](block: IntegrationTester => T): T = {
    val tester = new IntegrationTester(
      daemonMode,
      workspaceSourcePath,
      millExecutable,
      debugLog,
      baseWorkspacePath = os.pwd,
      propagateJavaHome = propagateJavaHome
    ) {
      override val workspacePath: Path = {
        // To preserve the repo dir name, create a directory and clone into it.
        val cwd = os.temp.dir(dir = baseWorkspacePath, deleteOnExit = false)
        os.proc("git", "clone", gitRepoUrl, "--depth", gitRepoDepth, "--branch", gitRepoBranch)
          .call(cwd = cwd)
        os.list(cwd).head
      }
      override def initWorkspace(): Unit = ()
    }
    try block(tester)
    finally tester.close()
  }
}
