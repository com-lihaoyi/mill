package mill.main

import ammonite.ops.pwd
import mill.Main.discoverMirror
import mill.define.Applicative.ApplyHandler
import mill.define.Task
import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.{OSet, PrintLogger}

class ReplApplyHandler(evaluator: Evaluator) extends ApplyHandler[Task] {
  // Evaluate classLoaderSig only once in the REPL to avoid busting caches
  // as the user enters more REPL commands and changes the classpath
  val classLoaderSig = Evaluator.classLoaderSig
  override def apply[V](t: Task[V]) = {
    evaluator.evaluate(OSet(t)).values.head.asInstanceOf[V]
  }
}
