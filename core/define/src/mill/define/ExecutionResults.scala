package mill.define

import mill.api.*

trait ExecutionResults {

  /**
   * The values returned by the tasks specified by the user
   */
  def results: Seq[ExecResult[Val]]

  /**
   * The full mapping of all tasks transitively upstream of the specified
   * tasks, and their results
   */
  def transitiveResults: Map[Task[?], ExecResult[Val]]

  /**
   * The tasks that were executed without being read from cache
   */
  def uncached: Seq[Task[?]]

  /**
   * The tasks and failures returned by failing tasks in [[transitiveResults]]
   */
  def transitiveFailing: Map[Task[?], ExecResult.Failing[Val]] =
    transitiveResults.collect { case (k, v: ExecResult.Failing[Val]) => (k, v) }

  /**
   * The values returned by successful tasks in [[results]]
   */
  def values: Seq[Val] = results.collect { case ExecResult.Success(v) => v }
}
