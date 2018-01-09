package mill.main


import mill.define.Applicative.ApplyHandler
import mill.define._
import mill.discover.{Discovered, Mirror}
import mill.eval.Evaluator
import mill.util.OSet

import scala.collection.mutable
object ReplApplyHandler{
  def apply[T](colors: ammonite.util.Colors,
               pprinter0: pprint.PPrinter,
               mapping: Discovered.Mapping[T]) = {
    new ReplApplyHandler(
      pprinter0,
      new mill.eval.Evaluator(
        ammonite.ops.pwd / 'out,
        ammonite.ops.pwd,
        mapping,
        new mill.util.PrintLogger(
          colors != ammonite.util.Colors.BlackWhite,
          colors,
          System.out,
          System.err,
          System.err
        )
      )
    )
  }
}
class ReplApplyHandler(pprinter0: pprint.PPrinter, evaluator: Evaluator[_]) extends ApplyHandler[Task] {
  // Evaluate classLoaderSig only once in the REPL to avoid busting caches
  // as the user enters more REPL commands and changes the classpath
  val classLoaderSig = Evaluator.classLoaderSig
  override def apply[V](t: Task[V]) = {
    evaluator.evaluate(OSet(t)).values.head.asInstanceOf[V]
  }

  val generatedEval = new EvalGenerated(evaluator)

  val millHandlers: PartialFunction[Any, pprint.Tree] = {
    case c: Cross[_] =>
      pprint.Tree.Lazy( ctx =>
        Iterator(c.ctx.enclosing , ":", c.ctx.lineNum.toString, ctx.applyPrefixColor("\nChildren:").toString) ++
        c.items.iterator.map(x =>
          "\n    (" + x._1.map(pprint.PPrinter.BlackWhite.apply(_)).mkString(", ") + ")"
        )
      )
    case m: mill.Module if evaluator.mapping.modulesToMirrors.contains(m) =>
      val mirror = evaluator.mapping.modulesToMirrors(m)
      pprint.Tree.Lazy( ctx =>
        Iterator(m.millModuleEnclosing, ":", m.millModuleLine.toString) ++
        (if (mirror.children.isEmpty) Nil
        else ctx.applyPrefixColor("\nChildren:").toString +: mirror.children.map("\n    ." + _._1)) ++
        (if (mirror.commands.isEmpty) Nil
        else ctx.applyPrefixColor("\nCommands:").toString +: mirror.commands.sortBy(_.name).map{c =>
          "\n    ." + c.name + "(" +
            c.argSignatures.map(s => s.name + ": " + s.typeString).mkString(", ") +
            ")()"
        }) ++
        (if (mirror.targets.isEmpty) Nil
        else ctx.applyPrefixColor("\nTargets:").toString +: mirror.targets.sortBy(_.label).map(t =>
          "\n    ." + t.label + "()"
        ))

      )
    case t: mill.define.Target[_] if evaluator.mapping.targets.contains(t) =>
      val seen = mutable.Set.empty[Task[_]]
      def rec(t: Task[_]): Seq[Segments] = {
        if (seen(t)) Nil // do nothing
        else t match {
          case t: Target[_] if evaluator.mapping.targets.contains(t) =>
            Seq(t.ctx.segments)
          case _ =>
            seen.add(t)
            t.inputs.flatMap(rec)
        }
      }
      pprint.Tree.Lazy(ctx =>
        Iterator(t.ctx.enclosing, ":", t.ctx.lineNum.toString, "\n", ctx.applyPrefixColor("Inputs:").toString) ++
        t.inputs.iterator.flatMap(rec).map("\n    " + _.render)
      )

  }
  val pprinter = pprinter0.copy(
    additionalHandlers = millHandlers orElse pprinter0.additionalHandlers
  )
}
