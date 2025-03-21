package mill.define

import mill.api.*

trait ExecutionResults {
  def results: Seq[ExecResult[Val]]
  def transitiveResults: Map[Task[?], ExecResult[Val]]
  def failing: Map[Task[?], ExecResult.Failing[Val]] =
    transitiveResults.collect{ case (k, v: ExecResult.Failing[Val]) => (k, v)}

  def values: Seq[Val] = results.collect { case ExecResult.Success(v) => v }
}
