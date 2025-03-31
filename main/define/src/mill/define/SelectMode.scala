package mill.define

sealed trait SelectMode

object SelectMode {

  /** All args are treated as targets or commands. If a `--` is detected, subsequent args are parameters to all commands. */
  object Multi extends SelectMode

  /** Args are treated as a task selector following its potential command parameters. The special separator (`++`) starts parsing another target/command selector. */
  object Separated extends SelectMode
}
