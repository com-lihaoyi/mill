package mill.resolve

@deprecated("Use mill.define.SelectMode instead", "Mill 0.12.10")
sealed trait SelectMode

@deprecated("Use mill.define.SelectMode instead", "Mill 0.12.10")
object SelectMode {

  /** All args are treated as targets or commands. If a `--` is detected, subsequent args are parameters to all commands. */
  @deprecated("Use mill.define.SelectMode.Multi instead", "Mill 0.12.10")
  object Multi extends SelectMode

  /** Args are treated as a task selector following its potential command parameters. The special separator (`++`) starts parsing another target/command selector. */
  @deprecated("Use mill.define.SelectMode.Separated instead", "Mill 0.12.10")
  object Separated extends SelectMode
}
