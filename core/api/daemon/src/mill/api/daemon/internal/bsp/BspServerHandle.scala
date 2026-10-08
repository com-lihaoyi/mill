package mill.api.daemon.internal.bsp

import scala.concurrent.Future

/** With this server handle you can interact with a running Mill BSP server. */
trait BspServerHandle {

  /**
   * Completes when the BSP server shuts down or asks the launcher to reload the workspace.
   */
  def shutdownFuture: Future[BspServerResult]

  /** Stops the BSP server. */
  def close(): Unit
}
