package mill.bsp

import mill.api.internal
import scala.deriving.Mirror

@internal
sealed trait BspServerResult

@internal
object BspServerResult {

  /** The session was to give mill a change to restart a new BSP session, which is required to load changes to the build setup. */
  object ReloadWorkspace extends BspServerResult

  /** The session or the server ended successfully. */
  object Shutdown extends BspServerResult

  /** The session or the server ended with a failure. */
  object Failure extends BspServerResult

  implicit val jsonifyReloadWorkspace: upickle.default.ReadWriter[ReloadWorkspace.type] =
    upickle.default.macroRW

  implicit val jsonifyShutdown: upickle.default.ReadWriter[Shutdown.type] =
    upickle.default.macroRW

  implicit val jsonifyFailure: upickle.default.ReadWriter[Failure.type] =
    upickle.default.macroRW

  implicit val jsonify: upickle.default.ReadWriter[BspServerResult] =
    upickle.default.macroRW

  // GENERATED CODE BY ci/scripts/manual_mirror_gen.sc - DO NOT EDIT
  private type SingletonMirrorProxy[T <: AnyRef & Singleton] =
    Mirror.SingletonProxy { val value: T }
  private def genSingletonMirror[T <: AnyRef & Singleton](ref: T): SingletonMirrorProxy[T] =
    new Mirror.SingletonProxy(ref).asInstanceOf[SingletonMirrorProxy[T]]
  private given Mirror_ReloadWorkspace: SingletonMirrorProxy[ReloadWorkspace.type] =
    genSingletonMirror(ReloadWorkspace)
  private given Mirror_Shutdown: SingletonMirrorProxy[Shutdown.type] =
    genSingletonMirror(Shutdown)
  private given Mirror_Failure: SingletonMirrorProxy[Failure.type] =
    genSingletonMirror(Failure)
}
