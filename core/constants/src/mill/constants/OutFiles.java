package mill.constants;

/**
 * Central place containing all the files that live inside the `out/` folder
 * and documentation about what they do
 */
public class OutFiles {

  private static final String envOutOrNull = System.getenv(EnvVars.MILL_OUTPUT_DIR);

  /**
   * Default hard-coded value for the Mill `out/` folder path. Unless you know
   * what you are doing, you should favor using [[out]] instead.
   */
  public static final String defaultOut = "out";

  /**
   * Path of the Mill `out/` folder
   */
  public static final String out = envOutOrNull == null ? defaultOut : envOutOrNull;

  /**
   * Path of the Mill "meta-build", used to compile the `build.sc` file so we can
   * run the primary Mill build. Can be nested for multiple stages of bootstrapping
   */
  public static final String millBuild = "mill-build";

  /**
   * A parallel performance and timing profile generated for every Mill execution.
   * Can be loaded into the Chrome browser chrome://tracing page to visualize where
   * time in a build is being spent
   */
  public static final String millChromeProfile = "mill-chrome-profile.json";

  /**
   * A sequential profile containing rich information about the tasks that were run
   * as part of a build: name, duration, cached, dependencies, etc. Useful to help
   * understand what tasks are taking time in a build run and why those tasks are
   * being executed
   */
  public static final String millProfile = "mill-profile.json";

  /**
   * Long-lived metadata about the Mill bootstrap process that persists between runs:
   * workers, watched files, classpaths, etc.
   */
  public static final String millRunnerState = "mill-runner-state.json";

  /**
   * Subfolder of `out/` that contains the machinery necessary for a single Mill background
   * server: metadata files, pipes, logs, etc.
   */
  public static final String millDaemon = "mill-daemon";

  /**
   * Subfolder of `out/` used to contain the Mill subprocess when run in no-server mode
   */
  public static final String millNoDaemon = "mill-no-daemon";

  /**
   * Lock file used for exclusive access to the Mill output directory
   */
  public static final String millOutLock = "mill-out-lock";

  /**
   * Any active Mill command that is currently run, for debugging purposes
   */
  public static final String millActiveCommand = "mill-active-command";

  /**
   * File used to store metadata related to selective execution, mostly
   * input hashes and method code signatures necessary to determine what
   * root tasks changed so Mill can decide which tasks to execute.
   */
  public static final String millSelectiveExecution = "mill-selective-execution.json";

  public static final String millDependencyTree = "mill-dependency-tree.json";
  public static final String millInvalidationTree = "mill-invalidation-tree.json";

  /**
   * Any active Mill command that is currently run, for debugging purposes
   */
  public static final String millJavaHome = "mill-java-home";
}
