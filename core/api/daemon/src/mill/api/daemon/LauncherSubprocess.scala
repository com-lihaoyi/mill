package mill.api.daemon

/**
 * Context for running interactive subprocesses via the launcher.
 */
object LauncherSubprocess
    extends scala.util.DynamicVariable[LauncherSubprocess.Runner](null) {
  case class Config(
      cmd: Seq[String],
      env: Map[String, String],
      cwd: String,
      timeoutMillis: Long = -1,
      mergeErrIntoOut: Boolean = false,
      propagateEnv: Boolean = true
  )

  trait Runner {
    def apply(config: Config): Int
  }
}
