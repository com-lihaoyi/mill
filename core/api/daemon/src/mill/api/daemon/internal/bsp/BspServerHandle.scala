package mill.api.daemon.internal.bsp

import scala.concurrent.Future

trait BspServerHandle {

  /**
   */
  def shutdownFuture: Future[BspServerResult]

  /** Stops the BSP server. */
  def close(): Unit
}
