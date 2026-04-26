package mill.daemon

import mill.api.daemon.Watchable
import mill.api.daemon.internal.{CompileProblemReporter, EvaluatorApi}

/**
 * Type alias for the BSP-worker-facing bootstrap bridge: each BSP request
 * runs the bootstrap (which materializes evaluators) and feeds the result
 * into the request body. Lives here so both the daemon-side caller in
 * [[MillMain0]] and the worker-side consumer in `mill.bsp.worker` can refer
 * to the same shape.
 */
private[daemon] object BspMode {
  type BootstrapBridge = [T] => (
      String,
      Int => Option[CompileProblemReporter],
      (Seq[EvaluatorApi], Seq[Watchable], Option[String]) => T
  ) => T
}
