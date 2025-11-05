package mill.javalib.zinc

import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.internal.ZincOperation

trait ZincApi {
  def apply(
      op: ZincOperation,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): op.Response
}
