package mill.api.daemon

/**
 * Context for running interactive subprocesses on the launcher in daemon mode.
 */
object LauncherSubprocess
    extends scala.util.DynamicVariable[Option[LauncherSubprocess.Runner]](None) {
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

  trait Runner {
    def apply(config: Config): Int
  }
}
