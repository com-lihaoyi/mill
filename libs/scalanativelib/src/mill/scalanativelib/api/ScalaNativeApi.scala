package mill.scalanativelib.api

import mill.api.JsonFormatters._
import upickle._

sealed abstract class LTO(val value: String)
object LTO {
  val values: Seq[LTO] = Seq(None, Thin, Full)

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
  val values: Seq[ReleaseMode] = Seq(Debug, ReleaseFast, ReleaseFull, ReleaseSize)

  case object Debug extends ReleaseMode("debug")
  case object ReleaseFast extends ReleaseMode("release-fast")
  case object ReleaseFull extends ReleaseMode("release-full")

  /**
   * Optimizes output binary size and still have relatively fast runtime performance.
   *  Equivalent to `-Oz` switch of `clang`.
   *  Since Scala Native 0.4.10
   */
  case object ReleaseSize extends ReleaseMode("release-size")

  implicit val rwDebug: ReadWriter[Debug.type] = macroRW[Debug.type]
  implicit val rwReleaseFast: ReadWriter[ReleaseFast.type] = macroRW[ReleaseFast.type]
  implicit val rwReleaseFull: ReadWriter[ReleaseFull.type] = macroRW[ReleaseFull.type]
  implicit val rwReleaseSize: ReadWriter[ReleaseSize.type] = macroRW[ReleaseSize.type]
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

sealed trait BuildTarget
object BuildTarget {

  /** Link code as application */
  case object Application extends BuildTarget

  /** Link code as shared/dynamic library */
  case object LibraryDynamic extends BuildTarget

  /** Link code as static library */
  case object LibraryStatic extends BuildTarget

  implicit val rwApplication: ReadWriter[Application.type] = macroRW[Application.type]
  implicit val rwLibraryDynamic: ReadWriter[LibraryDynamic.type] = macroRW[LibraryDynamic.type]
  implicit val rwLibraryStatic: ReadWriter[LibraryStatic.type] = macroRW[LibraryStatic.type]
  implicit val rw: ReadWriter[BuildTarget] = macroRW[BuildTarget]
}

/**
 * Shall toolchain enable mechanism for generation for source level debugging
 *  metadata.
 */
enum SourceLevelDebuggingConfig derives ReadWriter {
  case Enabled(
      /**
       * Shall function contain additional information about source definition.
       *  Enables source positions in stacktraces, but introduces a runtime penalty
       *  for symbols deserialization
       */
      generateFunctionSourcePositions: Boolean = true,
      /**
       * Shall generate a metadata for local variables, allows to check state of
       *  local variables in debugger. Recommended usage of LLDB with disabled
       *  optimizations.
       */
      generateLocalVariables: Boolean = true,
      /**
       * List of custom source roots used to map symbols find in binary file (NIR)
       *  with orignal Scala sources
       */
      customSourceRoots: Seq[os.Path] = Nil
  ) extends SourceLevelDebuggingConfig
  case Disabled
}
