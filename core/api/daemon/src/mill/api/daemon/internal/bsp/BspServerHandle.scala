package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.Watchable

import scala.concurrent.Future

/** With this server handle you can interact with a running Mill BSP server. */
trait BspServerHandle {

  /**
   * Starts a new session with the given evaluator. Doesn't block or wait for the session to end.
   */
  def startSession(
      evaluators: Seq[EvaluatorApi],
      errored: Boolean,
      watched: Seq[Watchable]
  ): Future[BspServerResult]

  def resetSession(): Unit

  /** Stops the BSP server. */
  def close(): Unit
}
