package mill.api.daemon.internal.bsp

sealed trait BspServerResult

object BspServerResult {

  /** The session was to give mill a change to restart a new BSP session, which is required to load changes to the build setup. */
  object ReloadWorkspace extends BspServerResult

  /** The session or the server ended successfully. */
  object Shutdown extends BspServerResult
}
