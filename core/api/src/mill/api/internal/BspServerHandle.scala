package mill.api.internal

import scala.concurrent.Future

/** With this server handle you can interact with a running Mill BSP server. */
trait BspServerHandle {

  /**
   * Starts a new session with the given evaluator. Doesn't block or wait for the session to end.
   */
  def startSession(evaluators: Seq[EvaluatorApi], errored: Boolean): Future[BspServerResult]

  /** Stops the BSP server. */
  def close(): Unit
}
