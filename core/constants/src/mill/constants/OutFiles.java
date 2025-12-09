package mill.constants;

/**
 * Central place containing all the files that live inside the `out/` folder
 * and documentation about what they do
 */
public class OutFiles {

  // single instance
  public static final OutFiles1 OutFiles = new OutFiles1();

  public static class OutFiles1 {
    // Using static final fiels is prone to Compiler inlining of stale values,
    // hence, we use a static instance with non-static field.
    private OutFiles1() {}

    /**
     * Allows users to override the `out/` folder from the environment via the {@link EnvVars#MILL_OUTPUT_DIR}
     * variable.
     */
    private final String envOutOrNull = System.getenv(EnvVars.MILL_OUTPUT_DIR);

    /**
     * Allows users to override to `out/` used in BSP server mode from the environment via the
     * {@link EnvVars#MILL_BSP_OUTPUT_DIR} variable.
     */
    private final String envBspOutOrNull = System.getenv(EnvVars.MILL_BSP_OUTPUT_DIR);

    /** @see EnvVars#MILL_NO_SEPARATE_BSP_OUTPUT_DIR */
    public final boolean mergeBspOut =
        // explicit request
        "1".equals(System.getenv(EnvVars.MILL_NO_SEPARATE_BSP_OUTPUT_DIR))
            // user specified MILL_OUTPUT_DIR but not MILL_BSP_OUTPUT_DIR
            || (envOutOrNull != null && envBspOutOrNull == null);

    /**
     * Default hard-coded value for the Mill `out/` folder path.
     * To get the effective out dir, use {@link #outFor}.
     */
    public final String defaultOut = "out";

    /**
     * Default hard-coded value for the Mill `out/` folder path in BSP server mode.
     * To get the effective out dir, use {@link #outFor}.
     */
    public final String defaultBspOut = ".bsp/mill-bsp-out";

    /**
     * Effective path of the Mill `out/` folder.
     * You should favor using {@link #outFor} instead.
     */
    public final String out = envOutOrNull != null ? envOutOrNull : defaultOut;

    /**
     * Effective path of the Mill `out/` folder when Mill is running in BSP mode.
     * You should favor using {@link #outFor} instead.
     */
    public final String bspOut =
        mergeBspOut ? out : envBspOutOrNull != null ? envBspOutOrNull : defaultBspOut;

    /**
     * Path of the Mill {@link #out} folder.
     *
     * @param outMode The mode for which the out folder is requested.
     */
    public String outFor(OutFolderMode outMode) {
      switch (outMode) {
        case REGULAR:
          return out;
        case BSP:
          return bspOut;
        default:
          throw new IllegalArgumentException("Unknown out folder mode: " + outMode);
      }
    }

    /**
     * Path of the Mill "meta-build", used to compile the `build.sc` file so we can
     * run the primary Mill build. Can be nested for multiple stages of bootstrapping
     */
    public final String millBuild = "mill-build";

    /**
     * A parallel performance and timing profile generated for every Mill execution.
     * Can be loaded into the Chrome browser chrome://tracing page to visualize where
     * time in a build is being spent
     */
    public final String millChromeProfile = "mill-chrome-profile.json";

    /**
     * A sequential profile containing rich information about the tasks that were run
     * as part of a build: name, duration, cached, dependencies, etc. Useful to help
     * understand what tasks are taking time in a build run and why those tasks are
     * being executed
     */
    public final String millProfile = "mill-profile.json";

    /**
     * Long-lived metadata about the Mill bootstrap process that persists between runs:
     * workers, watched files, classpaths, etc.
     */
    public final String millRunnerState = "mill-runner-state.json";

    /**
     * Subfolder of `out/` that contains the machinery necessary for a single Mill background
     * server: metadata files, pipes, logs, etc.
     */
    public final String millDaemon = "mill-daemon";

    /**
     * Subfolder of `out/` used to contain the Mill subprocess when run in no-server mode
     */
    public final String millNoDaemon = "mill-no-daemon";

    /**
     * Lock file used for exclusive access to the Mill output directory
     */
    public final String millOutLock = "mill-out-lock";

    /**
     * Any active Mill command that is currently run, for debugging purposes
     */
    public final String millActiveCommand = "mill-active-command";

    /**
     * File used to store metadata related to selective execution, mostly
     * input hashes and method code signatures necessary to determine what
     * root tasks changed so Mill can decide which tasks to execute.
     */
    public final String millSelectiveExecution = "mill-selective-execution.json";

    public final String millDependencyTree = "mill-dependency-tree.json";
    public final String millInvalidationTree = "mill-invalidation-tree.json";

    /**
     * Any active Mill command that is currently run, for debugging purposes
     */
    public final String millJavaHome = "mill-java-home";
  }

  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final boolean mergeBspOut = OutFiles.mergeBspOut;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String defaultOut = OutFiles.defaultOut;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String out = OutFiles.out;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String bspOut = OutFiles.bspOut;

  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static String outFor(OutFolderMode outMode) {
    return OutFiles.outFor(outMode);
  }

  public static final String millBuild = OutFiles.millBuild;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millChromeProfile = OutFiles.millChromeProfile;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millProfile = OutFiles.millProfile;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millRunnerState = OutFiles.millRunnerState;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millDaemon = OutFiles.millDaemon;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millNoDaemon = OutFiles.millNoDaemon;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millOutLock = OutFiles.millOutLock;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millActiveCommand = OutFiles.millActiveCommand;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millSelectiveExecution = OutFiles.millSelectiveExecution;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millDependencyTree = OutFiles.millDependencyTree;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millInvalidationTree = OutFiles.millInvalidationTree;
  /** @deprecated Use inner OutFiles instead, since Mill 1.1.0 */
  @Deprecated
  public static final String millJavaHome = OutFiles.millJavaHome;
}
