package mill.javalib.zinc

import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.internal.ZincOp

trait ZincApi {
  def apply(
      op: ZincOp,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): op.Response
}
