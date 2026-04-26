package mill.api.daemon.internal.bsp

import mill.api.daemon.Watchable
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi}

/**
 * Daemon-side hook the BSP worker calls once per BSP request. The worker
 * supplies the active command label, a meta-build compile reporter keyed by
 * depth, and the body that consumes the freshly-bootstrapped evaluators; the
 * daemon runs `MillBuildBootstrap` and threads the resulting evaluators (with
 * the watches captured during the run, plus any error message) into `body`.
 *
 * Declared as a SAM trait rather than a Scala 3 polymorphic-function alias so
 * the worker can locate the method via `Class.getMethod` without depending on
 * the polymorphic-function's erased shape (the previous `[T] => (a, b, c) => T`
 * alias erased to `scala.Function3` and caused a `NoSuchMethodException` at
 * the reflection boundary when the lookup mistakenly used `Function2`).
 */
trait BspBootstrapBridge {
  def apply[T](
      activeCommandMessage: String,
      metaBuildReporter: Int => Option[CompileProblemReporter],
      body: (Seq[EvaluatorApi], Seq[Watchable], Option[String]) => T
  ): T
}
