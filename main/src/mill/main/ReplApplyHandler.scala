package mill.main


import ammonite.ops.Path
import mill.define.Applicative.ApplyHandler
import mill.define.Segment.Label
import mill.define._
import mill.eval.{Evaluator, Result}
import mill.util.Strict.Agg

import scala.collection.mutable
object ReplApplyHandler{
  def apply[T](home: Path,
               disableTicker: Boolean,
               colors: ammonite.util.Colors,
               pprinter0: pprint.PPrinter,
               rootModule: mill.define.BaseModule,
               discover: Discover[_]) = {
    new ReplApplyHandler(
      pprinter0,
      new Evaluator(
        home,
        ammonite.ops.pwd / 'out,
        ammonite.ops.pwd / 'out,
        rootModule,
        new mill.util.PrintLogger(
          colors != ammonite.util.Colors.BlackWhite,
          disableTicker,
          colors,
          System.out,
          System.err,
          System.err,
          System.in
        )
      )
    )
  }
  def pprintCross(c: mill.define.Cross[_], evaluator: Evaluator[_]) = {
    pprint.Tree.Lazy( ctx =>
      Iterator(c.millOuterCtx.enclosing , ":", c.millOuterCtx.lineNum.toString, ctx.applyPrefixColor("\nChildren:").toString) ++
        c.items.iterator.map(x =>
          "\n    (" + x._1.map(pprint.PPrinter.BlackWhite.apply(_)).mkString(", ") + ")"
        )
    )
  }
  def pprintModule(m: mill.define.Module, evaluator: Evaluator[_]) = {
    pprint.Tree.Lazy( ctx =>
      Iterator(m.millInternal.millModuleEnclosing, ":", m.millInternal.millModuleLine.toString) ++
        (if (m.millInternal.reflectAll[mill.Module].isEmpty) Nil
        else
          ctx.applyPrefixColor("\nChildren:").toString +:
            m.millInternal.reflectAll[mill.Module].map("\n    ." + _.millOuterCtx.segment.pathSegments.mkString("."))) ++
        (evaluator.rootModule.millDiscover.value.get(m.getClass) match{
          case None => Nil
          case Some(commands) =>
            ctx.applyPrefixColor("\nCommands:").toString +: commands.map{c =>
              "\n    ." + c._2.name + "(" +
                c._2.argSignatures.map(s => s.name + ": " + s.typeString).mkString(", ") +
                ")()"
            }
        }) ++
        (if (m.millInternal.reflectAll[Target[_]].isEmpty) Nil
        else {
          Seq(ctx.applyPrefixColor("\nTargets:").toString) ++
            m.millInternal.reflectAll[Target[_]].map(t =>
              "\n    ." + t.label + "()"
            )
        })

    )
  }
  def pprintTask(t: NamedTask[_], evaluator: Evaluator[_]) = {
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
      Iterator(
        t.toString, "(", t.ctx.fileName.split('/').last, ":", t.ctx.lineNum.toString, ")",
        "\n", ctx.applyPrefixColor("Inputs:").toString
      ) ++ t.inputs.iterator.flatMap(rec).map("\n    " + _.render)
    )
  }

}
class ReplApplyHandler(pprinter0: pprint.PPrinter,
                       val evaluator: Evaluator[_]) extends ApplyHandler[Task] {
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
              case Result.Failure(m, _) => msg.append(m + "\n")
              case Result.Exception(t, outerStack) =>
                msg.append(
                  t.toString +
                  t.getStackTrace.dropRight(outerStack.value.length).map("\n    " + _).mkString +
                  "\n"
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
      ReplApplyHandler.pprintCross(c, evaluator)
    case m: mill.Module if evaluator.rootModule.millInternal.modules.contains(m) =>
      ReplApplyHandler.pprintModule(m, evaluator)
    case t: mill.define.Target[_] if evaluator.rootModule.millInternal.targets.contains(t) =>
      ReplApplyHandler.pprintTask(t, evaluator)

  }
  val pprinter = pprinter0.copy(
    additionalHandlers = millHandlers orElse pprinter0.additionalHandlers
  )
}
