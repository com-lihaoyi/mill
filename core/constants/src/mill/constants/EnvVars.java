package mill.constants;

/**
 * Central place containing all the environment variables that Mill uses
 */
public class EnvVars {
  // USER FACING ENVIRONMENT VARIABLES

  /**
   * Available in test modules for users to find the test resource folder on disk
   * in a convenient fashion. If multiple resource folders are provided on the classpath,
   * they are provided as a comma-separated list.
   * <p>
   * NOTE: only exists when running tests using Mill, and is not available when executing applications packaged
   * for deployment via .assembly
   */
  public static final String MILL_TEST_RESOURCE_DIR = "MILL_TEST_RESOURCE_DIR";

  /**
   * How long the Mill background daemon should run before timing out from inactivity
   */
  public static final String MILL_SERVER_TIMEOUT_MILLIS = "MILL_SERVER_TIMEOUT_MILLIS";

  /**
   * Output directory where Mill workers' state and Mill tasks output should be
   * written to
   */
  public static final String MILL_OUTPUT_DIR = "MILL_OUTPUT_DIR";

  /**
   * If set to "1", Mill will re-use the regular @{Link OutFiles#out} folder instead of
   * using a separate one for BSP output.
   */
  public static final String MILL_NO_SEPARATE_BSP_OUTPUT_DIR = "MILL_NO_SEPARATE_BSP_OUTPUT_DIR";

  // INTERNAL ENVIRONMENT VARIABLES
  /**
   * Used to pass the Mill workspace root from the client to the server, so
   * the server code can access it despite it not being os.pwd.
   *
   * Also, available in test modules for users to find the root folder of the
   * mill project on disk. Not intended for common usage, but sometimes necessary.
   */
  public static final String MILL_WORKSPACE_ROOT = "MILL_WORKSPACE_ROOT";

  /**
   * Used to indicate to Mill that it is running as part of the Mill test suite,
   * e.g. to turn on additional testing/debug/log-related code
   */
  public static final String MILL_TEST_SUITE = "MILL_TEST_SUITE";

  /**
   * The path to the currently executing Mill launcher executable
   */
  public static final String MILL_EXECUTABLE_PATH = "MILL_EXECUTABLE_PATH";

  /**
   * Env var used to enable certain compile-time checks only in the actual Mill process,
   * so that they don't cause false positives in IDEs like IntelliJ.
   */
  public static final String MILL_ENABLE_STATIC_CHECKS = "MILL_ENABLE_STATIC_CHECKS";
}
