package mill.util

import mill.api.internal

/**
 * Represents something that can be watched by the Mill build tool. Most often
 * these are [[Watchable.Path]]s, but we use [[Watchable.Value]] to watch the
 * value of arbitrary computations e.g. watching the result of `os.list` to
 * react if a new folder is added.
 */
@internal
private[mill] trait Watchable {
  def poll(): Long
  def signature: Long
  def validate(): Boolean = poll() == signature
  def pretty: String
}
@internal
private[mill] object Watchable {
  case class Path(p: mill.api.PathRef) extends Watchable {
    def poll() = p.recomputeSig()
    def signature = p.sig
    def pretty = p.toString
  }
  case class Value(f: () => Long, signature: Long, pretty: String) extends Watchable {
    def poll() = f()
  }
}
