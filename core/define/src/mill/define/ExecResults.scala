package mill.define

import mill.api.*

trait ExecutionResults {
  def rawValues: Seq[ExecResult[Val]]
  def evaluated: Seq[Task[?]]
  def failing: Map[Task[?], Seq[ExecResult.Failing[Val]]]
  def results: Map[Task[?], ExecResult[Val]]
  def values: Seq[Val] = rawValues.collect { case ExecResult.Success(v) => v }
}
