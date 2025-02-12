package mill.exec

import mill.api.*

import mill.define.*

trait ExecResults {
  def rawValues: Seq[ExecResult[Val]]
  def evaluated: Seq[Task[?]]
  def failing: Map[Task[?], Seq[ExecResult.Failing[Val]]]
  def results: Map[Task[?], TaskResult[Val]]
  def values: Seq[Val] = rawValues.collect { case ExecResult.Success(v) => v }
}
