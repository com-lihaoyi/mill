package mill.runner.api
//@internal
sealed trait BspServerResult

//@internal
object BspServerResult {

  /** The session was to give mill a change to restart a new BSP session, which is required to load changes to the build setup. */
  object ReloadWorkspace extends BspServerResult

  /** The session or the server ended successfully. */
  object Shutdown extends BspServerResult

  /** The session or the server ended with a failure. */
  object Failure extends BspServerResult
//
//  implicit val jsonifyReloadWorkspace: upickle.default.ReadWriter[ReloadWorkspace.type] =
//    upickle.default.macroRW
//
//  implicit val jsonifyShutdown: upickle.default.ReadWriter[Shutdown.type] =
//    upickle.default.macroRW
//
//  implicit val jsonifyFailure: upickle.default.ReadWriter[Failure.type] =
//    upickle.default.macroRW
//
//  implicit val jsonify: upickle.default.ReadWriter[BspServerResult] =
//    upickle.default.macroRW
//
//  private given Root_BspServerResult: Mirrors.Root[BspServerResult] =
//    Mirrors.autoRoot[BspServerResult]
}
