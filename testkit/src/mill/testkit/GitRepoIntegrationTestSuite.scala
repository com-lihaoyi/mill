package mill.testkit

import os.Path

trait GitRepoIntegrationTestSuite extends UtestIntegrationTestSuite {

  def integrationTestGitRepo[T](
      url: String,
      branch: String,
      linkMillExecutable: Boolean = false
  )(block: IntegrationTester => T): T = {
    val tester = new IntegrationTester(
      daemonMode,
      workspaceSourcePath,
      millExecutable,
      debugLog,
      baseWorkspacePath = os.pwd,
      propagateJavaHome = propagateJavaHome
    ) {
      override val workspacePath: Path = {
        val cwd = os.temp.dir(dir = baseWorkspacePath, deleteOnExit = false)
        // Clone into a new directory to preserve repo dir name.
        os.proc("git", "clone", url, "--depth", 1, "--branch", branch)
          .call(cwd = cwd)
        os.list(cwd).head
      }
      override def initWorkspace(): Unit = {}
    }
    if (linkMillExecutable) {
      val link = tester.workspacePath / "mill"
      if (!os.exists(link)) os.symlink(link, tester.millExecutable)
    }
    try block(tester)
    finally tester.close()
  }
}
