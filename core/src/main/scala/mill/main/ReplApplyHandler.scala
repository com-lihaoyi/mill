package mill.main

import ammonite.ops.pwd
import mill.Main.discoverMirror
import mill.define.Applicative.ApplyHandler
import mill.define.Task
import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.{OSet, PrintLogger}

class ReplApplyHandler[T: Discovered](obj: T) extends ApplyHandler[Task] {
  // Evaluate classLoaderSig only once in the REPL to avoid busting caches
  // as the user enters more REPL commands and changes the classpath
  val classLoaderSig = Evaluator.classLoaderSig
  override def apply[V](t: Task[V]) = discoverMirror(obj) match{
    case Left(err) =>
      throw new Exception("Failed discovery consistency check: " + err)
    case Right(mirror) =>
      val log = new PrintLogger(true)
      val evaluator = new Evaluator(
        pwd / 'out,
        Discovered.mapping(obj)(mirror),
        log,
        classLoaderSig = classLoaderSig
      )
      evaluator.evaluate(OSet(t)).values.head.asInstanceOf[V]
  }
}
