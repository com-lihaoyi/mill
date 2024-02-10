package mill.util

import mill.api.internal
import scala.util.control.NonFatal

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
    def poll(): Long = retryOnceRecomputeSig(p)
    def signature = p.sig
    def pretty = p.toString
  }
  case class Value(f: () => Long, signature: Long, pretty: String) extends Watchable {
    def poll(): Long = f()
  }

  /*
   * When a burst of nonatomic filesystem modifications occurs,
   * p.recomputeSig may fail. For example, if a file is deleted
   * quickly after another file has been saved (as occurs, for example,
   * with emacs lock files), recomputeSig may try to examine files it
   * has seen in an initial snapshot of the target directory, then
   * fail because they've been deleted from beneath it.
   *
   * If file are continuously getting added and deleted, there's little
   * hope of acquiring stable signatures as a basis for watching.
   *
   * But in the most common case, there's
   * a brief burst of changes, and then stability.
   *
   * This function pauses on an initial failure, then tries again
   * to find a stable signature, for this kind of case.
   *
   * The choice of 50 milliseconds is arbitrary, and a guess.
   */
  def retryOnceRecomputeSig(p: mill.api.PathRef): Long = {
    try {
      p.recomputeSig()
    } catch {
      case NonFatal(t) =>
        Thread.sleep(50)
        p.recomputeSig()
    }
  }
}
