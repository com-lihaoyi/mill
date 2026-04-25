package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.Watchable

/**
 * Bridge from the BSP worker into the daemon's bootstrap machinery.
 *
 * Each BSP request that needs evaluators calls [[runBootstrap]] to obtain
 * fresh evaluators+watches for the duration of `body`. After `body` returns,
 * the underlying [[mill.daemon.RunnerLauncherState]] is closed by the daemon —
 * meta-build read leases, retained task read leases, evaluators, and per-run
 * artifact bookkeeping are all torn down before [[runBootstrap]] returns.
 *
 * Concurrent BSP requests can each call [[runBootstrap]] independently. They
 * share the cached meta-build classloader via the daemon-wide
 * [[mill.daemon.RunnerSharedState]] and acquire their own read leases on
 * meta-build/task locks, so they coexist with parallel CLI commands without
 * any cross-request shared mutable evaluator cache in the BSP server.
 */
trait BspBootstrapBridge {

  /**
   * Run a bootstrap (`mill resolve _` against the user's `build.mill`),
   * invoke `body` with the resulting evaluators + accumulated `Watchable`
   * inputs, then tear down the launcher state and return body's result.
   *
   * `activeCommandMessage` becomes the active command name in the launcher
   * record (used in cross-launcher waiting messages).
   */
  def runBootstrap[T](
      activeCommandMessage: String,
      body: BspBootstrapBridge.Body[T]
  ): T
}

object BspBootstrapBridge {

  /**
   * Java-functional-interface form of the body: receives evaluators and the
   * watched inputs accumulated during this bootstrap. Defined as a SAM trait
   * (not Scala FunctionN) so the BSP-worker classloader can implement it
   * without depending on Scala's standard-library function classes resolved
   * against the daemon's classloader.
   */
  @FunctionalInterface
  trait Body[T] {
    def apply(evaluators: java.util.List[EvaluatorApi], watched: java.util.List[Watchable]): T
  }
}
