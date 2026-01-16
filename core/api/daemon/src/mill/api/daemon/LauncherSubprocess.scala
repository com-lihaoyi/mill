package mill.api.daemon

/**
 * Context for running subprocesses on the launcher (for interactive commands).
 * When set, repl/console/jshell will use this to run their subprocess on the launcher
 * with inherited stdin/stdout/stderr, allowing interactive terminal access.
 *
 * This is set by the daemon when running in daemon mode, and is None when running
 * in no-daemon mode.
 */
object LauncherSubprocess extends scala.util.DynamicVariable[Option[LauncherSubprocess.Runner]](None) {

  /**
   * Configuration for running a subprocess.
   *
   * @param cmd the command to run (executable and arguments)
   * @param env environment variables to set
   * @param cwd working directory as a string path
   * @param timeoutMillis timeout in milliseconds (-1 for no timeout)
   * @param mergeErrIntoOut whether to merge stderr into stdout
   * @param shutdownGracePeriodMillis grace period for shutdown (-1 for default)
   * @param propagateEnv whether to propagate the current environment variables
   * @param destroyOnExit whether to destroy the subprocess when the JVM exits
   */
  case class Config(
      cmd: Seq[String],
      env: Map[String, String],
      cwd: String,
      timeoutMillis: Long = -1,
      mergeErrIntoOut: Boolean = false,
      shutdownGracePeriodMillis: Long = -1,
      propagateEnv: Boolean = true,
      destroyOnExit: Boolean = true
  )

  /**
   * Runs subprocesses on the launcher side with inherited stdin/stdout/stderr.
   */
  trait Runner {
    def apply(config: Config): Int
  }
}
