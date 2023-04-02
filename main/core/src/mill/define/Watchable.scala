package mill.define

import mill.api.internal

/**
 * Represents something that can be watched by the Mill build tool. Most often
 * these are [[Watchable.Path]]s, but we use [[Watchable.Value]] to watch the
 * value of arbitrary computations e.g. watching the result of `os.list` to
 * react if a new folder is added.
 */
@internal
trait Watchable {
  def poll(): Long
  def signature: Long
  def validate(): Boolean = poll() == signature
}
@internal
object Watchable{
  case class Path(p: mill.api.PathRef) extends Watchable {
    def poll() = p.recomputeSig()
    def signature = p.sig
  }
  case class Value(f: () => Long, signature: Long) extends Watchable {
    def poll() = f()
  }
}
