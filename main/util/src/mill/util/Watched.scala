package mill.util

import mill.api.PathRef

case class Watched[T](value: T, watched: Seq[PathRef])
object Watched {
  implicit def readWrite[T: upickle.default.ReadWriter]: upickle.default.ReadWriter[Watched[T]] =
    upickle.default.macroRW[Watched[T]]
}
