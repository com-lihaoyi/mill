package mill.main

import mill.util.{PrintLogger, Watched}
import pprint.{Renderer, Truncated}

trait MainModule extends mill.Module{

  implicit def millDiscover: mill.define.Discover[_]
  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  def resolve(targets: mill.main.Tasks[Any]*) = mill.T.command{
    targets.flatMap(_.value).foreach(println)
  }
  def describe(evaluator: mill.eval.Evaluator[Any],
               targets: mill.main.Tasks[Any]*) = mill.T.command{
    for{
      t <- targets
      target <- t.value
      tree = ReplApplyHandler.pprintTask(target, evaluator)
      val defaults = pprint.PPrinter()
      val renderer = new Renderer(
        defaults.defaultWidth,
        defaults.colorApplyPrefix,
        defaults.colorLiteral,
        defaults.defaultIndent
      )
      val rendered = renderer.rec(tree, 0, 0).iter
      val truncated = new Truncated(rendered, defaults.defaultWidth, defaults.defaultHeight)
      str <- truncated ++ Iterator("\n")
    } {
      print(str)
    }
  }
  def all(evaluator: mill.eval.Evaluator[Any],
          targets: mill.main.Tasks[Any]*) = mill.T.command{
    val (watched, res) = RunScript.evaluate(
      evaluator,
      mill.util.Strict.Agg.from(targets.flatMap(_.value))
    )
    Watched((), watched)
  }
  def show(evaluator: mill.eval.Evaluator[Any],
           targets: mill.main.Tasks[Any]*) = mill.T.command{
    val (watched, res) = mill.main.RunScript.evaluate(
      // When using `show`, redirect all stdout of the evaluated tasks so the
      // printed JSON is the only thing printed to stdout.
      evaluator.copy(
        log = evaluator.log match{
          case PrintLogger(c1, c2, o, i, e) => PrintLogger(c1, c2, e, i, e)
          case l => l
        }
      ),
      mill.util.Strict.Agg.from(targets.flatMap(_.value))
    )
    for(json <- res.right.get.flatMap(_._2)){
      println(json)
    }
    Watched((), watched)
  }
}
