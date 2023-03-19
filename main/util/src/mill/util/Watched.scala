package mill.util

import mill.api.PathRef

case class Watched[T] private (value: T, watched: Seq[PathRef]) {
  def copy[T](value: T = value, watched: Seq[PathRef] = watched): Watched[T] =
    new Watched(value, watched)
}

object Watched {

  def apply[T](value: T, watched: Seq[PathRef]): Watched[T] = new Watched[T](value, watched)
  def unapply[T](watched: Watched[T]): Option[(T, Seq[PathRef])] =
    Option((watched.value, watched.watched))

  implicit def readWrite[T: upickle.default.ReadWriter]: upickle.default.ReadWriter[Watched[T]] =
    upickle.default.macroRW[Watched[T]]
}
