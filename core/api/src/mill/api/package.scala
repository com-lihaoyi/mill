package mill

package object api {
  val ClassLoader = mill.api.shared.ClassLoader

  val DummyInputStream = mill.api.shared.DummyInputStream

  val DummyOutputStream = mill.api.shared.DummyOutputStream

  val ExecResult = mill.api.shared.ExecResult
  type ExecResult[+T] = mill.api.shared.ExecResult[T]

  type experimental = mill.api.shared.experimental

  val FilesystemCheckerEnabled = mill.api.shared.FilesystemCheckerEnabled

  val Logger = mill.api.shared.Logger
  type Logger = mill.api.shared.Logger

  type MillException = mill.api.shared.MillException

  val MillURLClassLoader = mill.api.shared.MillURLClassLoader
  type MillURLClassLoader = mill.api.shared.MillURLClassLoader

  type ProxyLogger = mill.api.shared.ProxyLogger

  val Result = mill.api.shared.Result
  type Result[+T] = mill.api.shared.Result[T]

  val Segment = mill.api.shared.Segment
  type Segment = mill.api.shared.Segment

  val Segments = mill.api.shared.Segments
  type Segments = mill.api.shared.Segments

  val SelectMode = mill.api.shared.SelectMode
  type SelectMode = mill.api.shared.SelectMode

  val SystemStreams = mill.api.shared.SystemStreams
  type SystemStreams = mill.api.shared.SystemStreams

  val Val = mill.api.shared.Val
  type Val = mill.api.shared.Val

  val Watchable = mill.api.shared.Watchable
  type Watchable = mill.api.shared.Watchable
}
