package mill.api.daemon

sealed trait SelectMode

object SelectMode {

  /**
   * All args are treated as tasks or commands. If a `--` is detected,
   * subsequent args are parameters to all commands.
   */
  object Multi extends SelectMode

  /**
   * Like a combination of [[Single]] and [[Multi]], behaving like [[Single]]
   * but using a special separator (`++`) to start parsing another task/command.
   */
  object Separated extends SelectMode
}
