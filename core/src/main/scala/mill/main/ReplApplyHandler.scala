package mill.main


import mill.define.Applicative.ApplyHandler
import mill.define.Task
import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.OSet
object ReplApplyHandler{
  def apply[T](mapping: Discovered.Mapping[T]) = {
    new ReplApplyHandler(
      new mill.eval.Evaluator(
        ammonite.ops.pwd / 'out,
        mapping,
        new mill.util.PrintLogger(true)
      )
    )
  }
}
class ReplApplyHandler(evaluator: Evaluator[_]) extends ApplyHandler[Task] {
  // Evaluate classLoaderSig only once in the REPL to avoid busting caches
  // as the user enters more REPL commands and changes the classpath
  val classLoaderSig = Evaluator.classLoaderSig
  override def apply[V](t: Task[V]) = {
    evaluator.evaluate(OSet(t)).values.head.asInstanceOf[V]
  }
}
