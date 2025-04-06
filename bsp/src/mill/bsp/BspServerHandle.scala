package mill.bsp

import mill.api.internal
import mill.define.Evaluator

/** With this server handle you can interact with a running Mill BSP server. */
@internal
trait BspServerHandle {

  /**
   * Runs a new session with the given evaluator. This one blocks until the session ends.
   * @return The reason which the session ended, possibly indicating the wish for restart (e.g. in case of workspace reload).
   */
  def runSession(evaluators: Seq[Evaluator]): BspServerResult

  /** Stops the BSP server. */
  def close(): Unit
}
