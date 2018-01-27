package mill.main


import mill.define.Applicative.ApplyHandler
import mill.define.Segment.Label
import mill.define._
import mill.eval.{Evaluator, Result}
import mill.util.Strict.Agg

import scala.collection.mutable
object ReplApplyHandler{
  def apply[T](colors: ammonite.util.Colors,
               pprinter0: pprint.PPrinter,
               rootModule: mill.Module,
               discover: Discover) = {
    new ReplApplyHandler(
      pprinter0,
      new mill.eval.Evaluator(
        ammonite.ops.pwd / 'out,
        ammonite.ops.pwd,
        rootModule,
        discover,
        new mill.util.PrintLogger(
          colors != ammonite.util.Colors.BlackWhite,
          colors,
          System.out,
          System.err,
          System.err
        )
      ),
      discover
    )
  }
}
class ReplApplyHandler(pprinter0: pprint.PPrinter,
                       evaluator: Evaluator[_],
                       discover: Discover) extends ApplyHandler[Task] {
  // Evaluate classLoaderSig only once in the REPL to avoid busting caches
  // as the user enters more REPL commands and changes the classpath
  val classLoaderSig = Evaluator.classLoaderSig
  override def apply[V](t: Task[V]) = {
    val res = evaluator.evaluate(Agg(t))
    res.values match{
      case Seq(head: V) => head
      case Nil =>
        val msg = new mutable.StringBuilder()
        msg.append(res.failing.keyCount + " targets failed\n")
        for((k, vs) <- res.failing.items){
          msg.append(k match{
            case Left(t) => "Anonymous Task\n"
            case Right(k) => k.segments.render + "\n"
          })

          for(v <- vs){
            v match{
              case Result.Failure(m) => msg.append(m + "\n")
              case Result.Exception(t, outerStack) =>
                msg.append(
                  t.toString + t.getStackTrace.dropRight(outerStack.length).map("\n    " + _).mkString + "\n"
                )

            }
          }
        }
        throw new Exception(msg.toString)
    }
  }

  val generatedEval = new EvalGenerated(evaluator)

  val millHandlers: PartialFunction[Any, pprint.Tree] = {
    case c: Cross[_] =>
      pprint.Tree.Lazy( ctx =>
        Iterator(c.millOuterCtx.enclosing , ":", c.millOuterCtx.lineNum.toString, ctx.applyPrefixColor("\nChildren:").toString) ++
        c.items.iterator.map(x =>
          "\n    (" + x._1.map(pprint.PPrinter.BlackWhite.apply(_)).mkString(", ") + ")"
        )
      )
    case m: mill.Module if evaluator.rootModule.millInternal.modules.contains(m) =>
      pprint.Tree.Lazy( ctx =>
        Iterator(m.millInternal.millModuleEnclosing, ":", m.millInternal.millModuleLine.toString) ++
        (if (m.millInternal.reflect[mill.Module].isEmpty) Nil
        else
          ctx.applyPrefixColor("\nChildren:").toString +:
          m.millInternal.reflect[mill.Module].map("\n    ." + _.millOuterCtx.segments.render)) ++
        (discover.value.get(m.getClass) match{
          case None => Nil
          case Some(commands) =>
            ctx.applyPrefixColor("\nCommands:").toString +: commands.map{c =>
              "\n    ." + c._2.name + "(" +
              c._2.argSignatures.map(s => s.name + ": " + s.typeString).mkString(", ") +
                ")()"
            }
        }) ++
        (if (m.millInternal.reflect[Target[_]].isEmpty) Nil
        else {
          Seq(ctx.applyPrefixColor("\nTargets:").toString) ++
          m.millInternal.reflect[Target[_]].sortBy(_.label).map(t =>
            "\n    ." + t.label + "()"
          )
        })

      )
    case t: mill.define.Target[_] if evaluator.rootModule.millInternal.targets.contains(t) =>
      val seen = mutable.Set.empty[Task[_]]
      def rec(t: Task[_]): Seq[Segments] = {
        if (seen(t)) Nil // do nothing
        else t match {
          case t: Target[_] if evaluator.rootModule.millInternal.targets.contains(t) =>
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
