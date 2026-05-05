package mill.api.daemon.internal

import mill.api.daemon.Watchable

trait BuildFileApi {
  def rootModule: RootModuleApi
  def moduleWatchedValues: Seq[Watchable]

  /**
   * Run `body` with a fresh per-evaluation eval-watch buffer installed on the
   * current thread; returns the body result and the accumulated watches.
   * Concurrent evaluations get isolated buffers so they cannot lose or mix
   * each other's watches.
   */
  def withEvalWatchedValues[T](body: => T): (T, Seq[Watchable])
}
object BuildFileApi {
  class Bootstrap(val rootModule: RootModuleApi) extends BuildFileApi {
    def moduleWatchedValues = Nil
    def withEvalWatchedValues[T](body: => T): (T, Seq[Watchable]) = (body, Nil)
  }
}
