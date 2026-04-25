package mill.daemon

import mill.api.daemon.Watchable

/**
 * Immutable snapshot of a previous bootstrap's final-task watches and
 * selector. Used by [[MillBuildBootstrap.processFinalTasks]] to decide
 * whether the user-task evaluation can be skipped because nothing changed
 * since the previous bootstrap.
 *
 * Held data-only — never carries meta-build read leases or evaluators —
 * so an idle BSP server's cached snapshot does not block concurrent CLI
 * commands from acquiring meta-build write leases. Each request still
 * tears down its own [[RunnerLauncherState]] (and releases its own leases)
 * as soon as the request body returns.
 */
private[daemon] final case class BspPriorBootstrap(
    finalDepth: Int,
    tasksAndParams: Seq[String],
    evalWatched: Seq[Watchable],
    moduleWatched: Seq[Watchable]
)

private[daemon] object BspPriorBootstrap {

  /**
   * Capture the final-frame data from a successful bootstrap into a snapshot
   * suitable for short-circuit checks on a subsequent bootstrap. Returns
   * [[None]] when the state had no final frame (e.g. the bootstrap errored
   * before reaching task evaluation).
   */
  def from(state: RunnerLauncherState): Option[BspPriorBootstrap] =
    if (state.errorOpt.isDefined) None
    else state.finalFrame.map { f =>
      BspPriorBootstrap(
        finalDepth = f.depth,
        tasksAndParams = f.tasksAndParams,
        evalWatched = f.evalWatched,
        moduleWatched = f.moduleWatched
      )
    }
}
