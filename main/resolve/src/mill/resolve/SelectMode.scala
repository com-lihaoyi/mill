package mill.resolve

@deprecated("Use mill.define.SelectMode instead", "Mill 0.12.10")
sealed trait SelectMode

@deprecated("Use mill.define.SelectMode instead", "Mill 0.12.10")
object SelectMode {

  /** All args are treated as targets or commands. If a `--` is detected, subsequent args are parameters to all commands. */
  @deprecated("Use mill.define.SelectMode.Multi instead", "Mill 0.12.10")
  object Multi extends SelectMode

  /** Like a combination of [[Single]] and [[Multi]], behaving like [[Single]] but using a special separator (`++`) to start parsing another target/command. */
  @deprecated("Use mill.define.SelectMode.Separated instead", "Mill 0.12.10")
  object Separated extends SelectMode
}
