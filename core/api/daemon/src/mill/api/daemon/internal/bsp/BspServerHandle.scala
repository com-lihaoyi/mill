package mill.api.daemon.internal.bsp

import scala.concurrent.Future

/**
 * Daemon-side handle to a running BSP server.
 *
 * In the simplified architecture, the BSP server no longer publishes a
 * long-lived "session" of evaluators — each BSP request runs its own
 * bootstrap via [[BspBootstrapBridge]]. The daemon's only handle into the
 * server is therefore [[shutdownFuture]], which completes when the BSP
 * client sends `build/exit` or `workspace/reload`, and [[close]] for forced
 * teardown.
 */
trait BspServerHandle {

  /**
   * Completes when the BSP client signals end-of-session (e.g. `build/exit`)
   * or requests a workspace reload. The daemon's BSP block awaits this
   * future and exits when it resolves.
   */
  def shutdownFuture: Future[BspServerResult]

  /** Stops the BSP server. */
  def close(): Unit
}
