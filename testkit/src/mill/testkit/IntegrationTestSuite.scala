package mill.testkit

import mill.util.Retry
import os.Path

import scala.concurrent.duration.DurationInt

trait IntegrationTestSuite {

  /**
   * Where the project root of the Mill codebase under test is located on disk.
   */
  protected def workspaceSourcePath: os.Path

  /**
   * If `true`, run Mill subprocesss normally as a client to a long-lived
   * background daemon. If `false`, run the Mill subprocess with `--no-server`
   * so it exits after every command. Both are useful depending on what you
   * are trying to test, and generally Mill builds are expected to behave the
   * same in both modes (except for performance differences due to in-memory caching)
   */
  protected def daemonMode: Boolean

  /**
   * Path to the Mill executable to use to run integration tests with
   */
  protected def millExecutable: Path

  /**
   * Whether to pass the `java.home` property from the test runner to the Mill
   * test process as the `JAVA_HOME` environment variable. This is usually what
   * you want, since it ensures the JVM version is consistent, but occasionally
   * you may want to disable propagation because you explicitly want to test the
   * Mill subprocess using a different JVM version
   */
  protected def propagateJavaHome: Boolean = true

  protected def cleanupProcessIdFile: Boolean = true
  def debugLog: Boolean = false

  /**
   * Run an integration test by providing an [[IntegrationTester]] to the
   * given [[block]].
   */
  def integrationTest[T](block: IntegrationTester => T): T = {
    Retry(
      logger = Retry.printStreamLogger(System.err),
      count = if (sys.env.contains("CI")) 1 else 0,
      timeoutMillis = 10.minutes.toMillis
    ) {
      val tester = IntegrationTester(
        daemonMode,
        workspaceSourcePath,
        millExecutable,
        debugLog,
        baseWorkspacePath = os.pwd,
        propagateJavaHome = propagateJavaHome,
        cleanupProcessIdFile = cleanupProcessIdFile
      )
      try block(tester)
      finally tester.close()
    }
  }
}
