package mill.main.client

/**
 * Central place containing all the files that live inside the `out/` folder and
 * documentation about what they do
 */
object OutFiles {

  private val envOutOrNull: String = sys.env.getOrElse(EnvVars.MILL_OUTPUT_DIR, null)

  /**
   * Default hard-coded value for the Mill `out/` folder path. Unless you know
   * what you are doing, you should favor using `out` instead.
   */
  val defaultOut: String = "out"

  /**
   * Path of the Mill `out/` folder
   */
  val out: String = Option(envOutOrNull).getOrElse(defaultOut)

  /**
   * Path of the Mill "meta-build", used to compile the `build.sc` file so we can
   * run the primary Mill build. Can be nested for multiple stages of
   * bootstrapping
   */
  val millBuild: String = "mill-build"

  /**
   * A parallel performance and timing profile generated for every Mill execution.
   * Can be loaded into the Chrome browser chrome://tracing page to visualize
   * where time in a build is being spent
   */
  val millChromeProfile: String = "mill-chrome-profile.json"

  /**
   * A sequential profile containing rich information about the tasks that were
   * run as part of a build: name, duration, cached, dependencies, etc. Useful to
   * help understand what tasks are taking time in a build run and why those tasks
   * are being executed
   */
  val millProfile: String = "mill-profile.json"

  /**
   * Long-lived metadata about the Mill bootstrap process that persists between
   * runs: workers, watched files, classpaths, etc.
   */
  val millRunnerState: String = "mill-runner-state.json"

  /**
   * Subfolder of `out/` that contains the machinery necessary for a single Mill
   * background server: metadata files, pipes, logs, etc.
   */
  val millServer: String = "mill-server"

  /**
   * Subfolder of `out/` used to contain the Mill subprocess when run in no-server
   * mode
   */
  val millNoServer: String = "mill-no-server"

  /**
   * Lock file used for exclusive access to the Mill output directory
   */
  val millLock: String = "mill-lock"

  /**
   * Any active Mill command that is currently run, for debugging purposes
   */
  val millActiveCommand: String = "mill-active-command"
}
