package mill.scalalib.buildfile

import mill.api.{PathRef, experimental}
import upickle.default.{ReadWriter, macroRW}

@experimental
case class WrappedSource(orig: PathRef, wrapped: Option[PathRef])

@experimental
object WrappedSource {
  implicit val upickleRW: ReadWriter[WrappedSource] = macroRW
}
