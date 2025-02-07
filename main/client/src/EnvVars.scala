package mill.main.client

/**
 * Central place containing all the environment variables that Mill uses
 */
object EnvVars {
  // USER FACING ENVIRONMENT VARIABLES

  /**
   * Available in test modules for users to find the test resource folder on disk
   * in a convenient fashion. If multiple resource folders are provided on the classpath,
   * they are provided as a comma-separated list
   */
  val MILL_TEST_RESOURCE_DIR: String = "MILL_TEST_RESOURCE_DIR"

  /**
   * How long the Mill background server should run before timing out from inactivity
   */
  val MILL_SERVER_TIMEOUT_MILLIS: String = "MILL_SERVER_TIMEOUT_MILLIS"

  val MILL_JVM_OPTS_PATH: String = "MILL_JVM_OPTS_PATH"
  val MILL_OPTS_PATH: String = "MILL_OPTS_PATH"

  /**
   * Output directory where Mill workers' state and Mill tasks output should be
   * written to
   */
  val MILL_OUTPUT_DIR: String = "MILL_OUTPUT_DIR"

  // INTERNAL ENVIRONMENT VARIABLES
  /**
   * Used to pass the Mill workspace root from the client to the server, so
   * the server code can access it despite it not being os.pwd.
   *
   * Also, available in test modules for users to find the root folder of the
   * mill project on disk. Not intended for common usage, but sometimes necessary.
   */
  val MILL_WORKSPACE_ROOT: String = "MILL_WORKSPACE_ROOT"

  /**
   * Used to indicate to Mill that it is running as part of the Mill test suite,
   * e.g. to turn on additional testing/debug/log-related code
   */
  val MILL_TEST_SUITE: String = "MILL_TEST_SUITE"

  /**
   * Used to indicate to the Mill test suite which libraries should be resolved from
   * the local disk and not from Maven Central
   */
  val MILL_BUILD_LIBRARIES: String = "MILL_BUILD_LIBRARIES"
}
