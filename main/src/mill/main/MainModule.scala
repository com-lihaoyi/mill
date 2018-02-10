package mill.main

import mill.util.{PrintLogger, Watched}
import pprint.{Renderer, Truncated}

trait MainModule extends mill.Module{

  implicit def millDiscover: mill.define.Discover[_]
  implicit def millScoptTasksReads[T] = new mill.main.Tasks.Scopt[T]()
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  def resolve(evaluator: mill.eval.Evaluator[Any], targets: String*) = mill.T.command{
    RunScript.resolveTargets(evaluator, targets, multiSelect = true) match{
      case Left(err) => mill.eval.Result.Failure(err)
      case Right(tasks) => mill.eval.Result.Success(tasks.foreach(println))
    }
  }

  def describe(evaluator: mill.eval.Evaluator[Any], targets: String*) = mill.T.command{
    RunScript.resolveTargets(evaluator, targets, multiSelect = true) match{
      case Left(err) => mill.eval.Result.Failure(err)
      case Right(tasks) =>
        for{
          task <- tasks
          tree = ReplApplyHandler.pprintTask(task, evaluator)
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
        mill.eval.Result.Success(())
    }
  }

  def all(evaluator: mill.eval.Evaluator[Any],
          targets: String*) = mill.T.command{
    RunScript.evaluateTarget(evaluator, targets, multiSelect = true) match{
      case Left(err) => mill.eval.Result.Failure(err)
      case Right((watched, Left(err))) => mill.eval.Result.Failure(err, Some(Watched((), watched)))
      case Right((watched, Right(res))) => mill.eval.Result.Success(Watched((), watched))
    }
  }

  def show(evaluator: mill.eval.Evaluator[Any],
           targets: String*) = mill.T.command{
    RunScript.evaluateTarget(
      evaluator.copy(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        log = evaluator.log match{
          case PrintLogger(c1, c2, o, i, e) => PrintLogger(c1, c2, e, i, e)
          case l => l
        }
      ),
      targets,
      multiSelect = false
    ) match{
      case Left(err) => mill.eval.Result.Failure(err)
      case Right((watched, Left(err))) => mill.eval.Result.Failure(err, Some(Watched((), watched)))
      case Right((watched, Right(res))) =>
        for(json <- res.flatMap(_._2)){
          println(json)
        }
        mill.eval.Result.Success(Watched((), watched))
    }
  }
}
