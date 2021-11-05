package mill.main

import mill.eval.Evaluator

/** With this server handle you can interact with a running Mill BSP server. */
trait BspServerHandle {

  /**
   * Runs a new session with the given evaluator. This one blocks until the session ends.
   * @return The reason which the session ended, possibly indictating the wish for restart (e.g. in case of workspace reload).
   */
  def runSession(evaluator: Evaluator): BspServerResult

  /**
   * The result of the latest started session. Once a new session was started but not finished, this may be [[None]].
   * @return The result of the latest ran session or [[None]] if there weren't any session or the session is currently running.
   */
  def lastResult: Option[BspServerResult]

  /** Stops the BSP server. */
  def stop(): Unit
}
