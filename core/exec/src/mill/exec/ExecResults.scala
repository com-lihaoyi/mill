package mill.exec

import mill.api.*
import mill.api.Strict.Agg
import mill.define.*

trait ExecResults {
  def rawValues: Seq[Result[Val]]
  def evaluated: Agg[Task[?]]
  def failing: Map[Task[?], Seq[Result.Failing[Val]]]
  def results: Map[Task[?], TaskResult[Val]]
  def values: Seq[Val] = rawValues.collect { case Result.Success(v) => v }
}
