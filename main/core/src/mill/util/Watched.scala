package mill.util

import mill.eval.PathRef

case class Watched[T](value: T, watched: Seq[PathRef])
object Watched{
  implicit def readWrite[T: upickle.default.ReadWriter] = upickle.default.macroRW[Watched[T]]
}
