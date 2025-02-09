package mill.eval
import mill.api.*
import mill.api.Strict.Agg
import mill.define.*
import mill.internal.MultiBiMap

trait EvalResults {
  def rawValues: Seq[Result[Val]]
  def evaluated: Agg[Task[?]]
  def transitive: Agg[Task[?]]
  def failing: MultiBiMap[Task[?], Result.Failing[Val]]
  def results: collection.Map[Task[?], TaskResult[Val]]
  def values: Seq[Val] = rawValues.collect { case Result.Success(v) => v }
}

