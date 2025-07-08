package mill.api.daemon

/**
 * Represents something that can be watched by the Mill build tool. Most often
 * these are [[Watchable.Path]]s, but we use [[Watchable.Value]] to watch the
 * value of arbitrary computations e.g. watching the result of `os.list` to
 * react if a new folder is added.
 */
private[mill] sealed trait Watchable
private[mill] object Watchable {

  /**
   * Watched path, can be watched via polling or via a notification system.
   *
   * @param p the path to watch
   * @param quick if true, only watch file attributes
   * @param signature the initial hash of the path contents
   */
  case class Path(p: java.nio.file.Path, quick: Boolean, signature: Int) extends Watchable

  /**
   * Watched expression, can only be watched via polling.
   *
   * @param f the expression to watch, returns some sort of hash
   * @param signature the initial hash from the first invocation of the expression
   * @param pretty human-readable name
   */
  case class Value(f: () => Long, signature: Long, pretty: String) extends Watchable
}
