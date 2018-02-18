package mill.main

import mill.define.NamedTask
import mill.eval.{Evaluator, Result}
import mill.util.{EitherOps, ParseArgs, PrintLogger, Watched}
import pprint.{Renderer, Truncated}
import upickle.Js
object MainModule{
  def resolveTasks[T](evaluator: Evaluator[Any], targets: Seq[String], multiSelect: Boolean)
                     (f: List[NamedTask[Any]] => T) = {
    RunScript.resolveTasks(mill.main.Resolve, evaluator, targets, multiSelect) match{
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }
  def evaluateTasks[T](evaluator: Evaluator[Any], targets: Seq[String], multiSelect: Boolean)
                      (f: Seq[(Any, Option[Js.Value])] => T) = {
    RunScript.evaluateTasks(evaluator, targets, multiSelect) match{
      case Left(err) => Result.Failure(err)
      case Right((watched, Left(err))) => Result.Failure(err, Some(Watched((), watched)))
      case Right((watched, Right(res))) =>
        f(res)
        Result.Success(Watched((), watched))
    }
  }
}

trait MainModule extends mill.Module{

  implicit def millDiscover: mill.define.Discover[_]
  implicit def millScoptTasksReads[T] = new mill.main.Tasks.Scopt[T]()
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  def resolve(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveMetadata, evaluator, targets, multiSelect = true
    )
    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(r) => Result.Success(r.foreach(println))
    }
  }

  def describe(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    MainModule.resolveTasks(evaluator, targets, multiSelect = true){ tasks =>
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
    }
  }

  def all(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    MainModule.evaluateTasks(evaluator, targets, multiSelect = true) {res =>
      res.flatMap(_._2)
    }
  }

  def show(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    MainModule.evaluateTasks(
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
    ) {res =>
      for(json <- res.flatMap(_._2)){
        println(json)
      }
    }
  }
}
