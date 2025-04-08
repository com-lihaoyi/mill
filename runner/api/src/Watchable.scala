package mill.runner.api

/**
 * Represents something that can be watched by the Mill build tool. Most often
 * these are [[Watchable.Path]]s, but we use [[Watchable.Value]] to watch the
 * value of arbitrary computations e.g. watching the result of `os.list` to
 * react if a new folder is added.
 */
private[mill] sealed trait Watchable
private[mill] object Watchable {
  case class Path(p: java.nio.file.Path, quick: Boolean, signature: Int) extends Watchable
  case class Value(f: () => Long, signature: Long, pretty: String) extends Watchable
}
