package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import mill.util.Retry

trait MillInitMigrateTestSuite extends UtestIntegrationTestSuite {

  def integrationTestGitRepo[T](
      url: String,
      branch: String,
      depth: Int = 1
  )(block: IntegrationTester => T) =
    Retry(
      logger = Retry.printStreamLogger(System.err),
      count = if (sys.env.contains("CI")) 1 else 0,
      timeoutMillis = 10 * 60 * 1000
    ) {
      // When cloning, the repo dir name should be preserved since init might use it.
      // Also, there is no copy-resources-for-setup requirement.
      // TODO Replace with a custom tester???
      val tester = new IntegrationTester(
        daemonMode,
        workspaceSourcePath,
        millExecutable,
        debugLog,
        baseWorkspacePath = os.pwd,
        propagateJavaHome = propagateJavaHome
      ) {
        override val workspacePath = {
          // same as super.workspacePath
          val parentDir = Iterator
            .iterate(1)(_ + 1)
            .map(i => baseWorkspacePath / s"run-$i")
            .find(!os.exists(_))
            .head
          os.makeDir.all(parentDir)
          os.proc("git", "clone", "--depth", depth, url, "--branch", branch)
            .call(cwd = parentDir)
          os.list(parentDir).head
        }
        override def initWorkspace() = {}
      }
      try block(tester)
      finally tester.close()
    }
}
