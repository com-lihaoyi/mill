package mill.scalanativelib.api

import upickle.default._

sealed abstract class LTO(val value: String)
object LTO {
  val values = Seq(None, Thin, Full)

  case object None extends LTO("none")
  case object Thin extends LTO("thin")
  case object Full extends LTO("full")

  implicit val rwNone: ReadWriter[None.type] = macroRW[None.type]
  implicit val rwThin: ReadWriter[Thin.type] = macroRW[Thin.type]
  implicit val rwFull: ReadWriter[Full.type] = macroRW[Full.type]
  implicit val rw: ReadWriter[LTO] = macroRW[LTO]
}

sealed abstract class ReleaseMode(val value: String)
object ReleaseMode {
  val values = Seq(Debug, ReleaseFast, ReleaseFull)

  case object Debug extends ReleaseMode("debug")
  case object ReleaseFast extends ReleaseMode("release-fast")
  case object ReleaseFull extends ReleaseMode("release-full")

  implicit val rwDebug: ReadWriter[Debug.type] = macroRW[Debug.type]
  implicit val rwReleaseFast: ReadWriter[ReleaseFast.type] = macroRW[ReleaseFast.type]
  implicit val rwReleaseFull: ReadWriter[ReleaseFull.type] = macroRW[ReleaseFull.type]
  implicit val rw: ReadWriter[ReleaseMode] = macroRW[ReleaseMode]
}

sealed trait NativeLogLevel
object NativeLogLevel {
  case object Error extends NativeLogLevel
  case object Warn extends NativeLogLevel
  case object Info extends NativeLogLevel
  case object Debug extends NativeLogLevel
  case object Trace extends NativeLogLevel

  implicit val rwError: ReadWriter[Error.type] = macroRW[Error.type]
  implicit val rwWarn: ReadWriter[Warn.type] = macroRW[Warn.type]
  implicit val rwInfo: ReadWriter[Info.type] = macroRW[Info.type]
  implicit val rwDebug: ReadWriter[Debug.type] = macroRW[Debug.type]
  implicit val rwTrace: ReadWriter[Trace.type] = macroRW[Trace.type]

  implicit val rw: ReadWriter[NativeLogLevel] = macroRW[NativeLogLevel]
}

class NativeConfig(val config: Object)
object NativeConfig {
  def apply(config: Object): NativeConfig =
    new NativeConfig(config)
}
