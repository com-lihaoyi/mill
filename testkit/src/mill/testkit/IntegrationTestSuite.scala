package mill.testkit

import os.Path

trait IntegrationTestSuite {
  /**
   * Where the project root of the Mill codebase under test is located on disk.
   */
  protected def workspaceSourcePath: os.Path

  /**
   * If `true`, run Mill subprocesss normally as a client to a long-lived
   * background server. If `false`, run the Mill subprocess with `--no-server`
   * so it exits after every command. Both are useful depending on what you
   * are trying to test, and generally Mill builds are expected to behave the
   * same in both modes (except for performance differences due to in-memory caching)
   */
  protected def clientServerMode: Boolean

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

  def debugLog: Boolean = false

  /**
   * Run an integration test by providing an [[IntegrationTester]] to the
   * given [[block]].
   */
  def integrationTest[T](block: IntegrationTester => T): T = {
    val tester = new IntegrationTester(
      clientServerMode,
      workspaceSourcePath,
      millExecutable,
      debugLog,
      baseWorkspacePath = os.pwd,
      propagateJavaHome = propagateJavaHome
    )
    try block(tester)
    finally tester.close()
  }
}
