package mill.main

import java.util.concurrent.LinkedBlockingQueue

import mill.T
import mill.define.{NamedTask, Task}
import mill.eval.{Evaluator, PathRef, Result}
import mill.util.{Ctx, PrintLogger, Watched}
import pprint.{Renderer, Truncated}
import upickle.Js
object MainModule{
  def resolveTasks[T](evaluator: Evaluator, targets: Seq[String], multiSelect: Boolean)
                     (f: List[NamedTask[Any]] => T) = {
    RunScript.resolveTasks(mill.main.ResolveTasks, evaluator, targets, multiSelect) match{
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }
  def evaluateTasks[T](evaluator: Evaluator, targets: Seq[String], multiSelect: Boolean)
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
  def version() = mill.T.command {
    val res = System.getProperty("MILL_VERSION")
    println(res)
    res
  }

  private val OutDir: String = "out"

  /**
    * Resolves a mill query string and prints out the tasks it resolves to.
    */
  def resolve(evaluator: Evaluator, targets: String*) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveMetadata, evaluator, targets, multiSelect = true
    )

    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(rs) =>
        for(r <- rs.sorted) {
          println(r)
        }
        Result.Success(())
    }
  }

  /**
    * Given a set of tasks, prints out the execution plan of what tasks will be
    * executed in what order, without actually executing them.
    */
  def plan(evaluator: Evaluator, targets: String*) = mill.T.command{
    plan0(evaluator, targets) match{
      case Right(success) => {
        val renderedTasks = success.map{ _.segments.render}
        renderedTasks.foreach(println)
        Result.Success(renderedTasks)
      }
      case Left(err) => Result.Failure(err)
    }
  }

  private def plan0(evaluator: Evaluator, targets: Seq[String]) = {
    RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, targets, multiSelect = true
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = Evaluator.plan(evaluator.rootModule, rs)
        Right(sortedGroups.keys().collect{ case Right(r) => r}.toArray)
    }
  }

  /**
    * Prints out some dependency path from the `src` task to the `dest` task.
    *
    * If there are multiple dependency paths between `src` and `dest`, the path
    * chosen is arbitrary.
    */
  def path(evaluator: Evaluator, src: String, dest: String) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, List(src, dest), multiSelect = true
    )

    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(Seq(src1, dest1)) =>
        val queue = collection.mutable.Queue[List[Task[_]]](List(src1))
        var found = Option.empty[List[Task[_]]]
        val seen = collection.mutable.Set.empty[Task[_]]
        while(queue.nonEmpty && found.isEmpty){
          val current = queue.dequeue()
          if (current.head == dest1) found = Some(current)
          else{
            for{
              next <- current.head.inputs
              if !seen.contains(next)
            }{
              seen.add(next)
              queue.enqueue(next :: current)
            }
          }
        }
        found match{
          case None =>
            Result.Failure(s"No path found between $src and $dest")
          case Some(list) =>
            val labels = list
              .collect{case n: NamedTask[_] => n.ctx.segments.render}

            labels.foreach(mill.T.ctx().log.outputStream.println(_))

            Result.Success(labels)
        }
    }
  }

  /**
    * Displays metadata about the given task without actually running it.
    */
  def inspect(evaluator: Evaluator, targets: String*) = mill.T.command{
    MainModule.resolveTasks(evaluator, targets, multiSelect = true){ tasks =>
      val output = new StringBuilder
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
        output.append(str)
      }
      println(output)
      output.toString
    }
  }

  /**
    * Runs multiple tasks in a single call.
    *
    *
    */
  def all(evaluator: Evaluator, targets: String*) = mill.T.command{
    MainModule.evaluateTasks(evaluator, targets, multiSelect = true) {res =>
      res.flatMap(_._2)
    }
  }

  /**
    * Runs a given task and prints the JSON result to stdout. This is useful
    * to integrate Mill into external scripts and tooling.
    */
  def show(evaluator: Evaluator, targets: String*) = mill.T.command{
    MainModule.evaluateTasks(
      evaluator.copy(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        log = evaluator.log match{
          case PrintLogger(c1, d, c2, o, i, e, in, de) => PrintLogger(c1, d, c2, e, i, e, in, de)
          case l => l
        }
      ),
      targets,
      multiSelect = false
    ) {res =>
      for(json <- res.flatMap(_._2)){
        println(json.render(indent = 4))
      }
    }
  }

  /**
    * Deletes the given targets from the out directory. Providing no targets
    * will clean everything.
    */
  def clean(evaluator: Evaluator, targets: String*) = mill.T.command {
    val rootDir = ammonite.ops.pwd / OutDir

    val KeepPattern = "(mill-.+)".r.anchored

    def keepPath(path: os.Path) = path.segments.toSeq.lastOption match {
      case Some(KeepPattern(_)) => true
      case _ => false
    }

    val pathsToRemove =
      if (targets.isEmpty)
        Right(ammonite.ops.ls(rootDir).filterNot(keepPath))
      else
        RunScript.resolveTasks(
          mill.main.ResolveSegments, evaluator, targets, multiSelect = true
        ).map(
          _.map { segments =>
            Evaluator.resolveDestPaths(rootDir, segments).out
          })

    pathsToRemove match {
      case Left(err) =>
        Result.Failure(err)
      case Right(paths) =>
        paths.foreach(os.remove.all)
        Result.Success(())
    }
  }


  /**
    * Renders the dependencies between the given tasks as a SVG for you to look at
    */
  def visualize(evaluator: Evaluator, targets: String*) = mill.T.command{
    visualize0(evaluator, targets, T.ctx(), mill.main.VisualizeModule.worker())
  }

  /**
    * Renders the dependencies between the given tasks, and all their dependencies, as a SVG
    */
  def visualizePlan(evaluator: Evaluator, targets: String*) = mill.T.command{
    plan0(evaluator, targets) match {
      case Left(err) => Result.Failure(err)
      case Right(planResults) => visualize0(
        evaluator, targets, T.ctx(), mill.main.VisualizeModule.worker(), Some(planResults.toList.map(_.task))
      )
    }
  }

  private type VizWorker = (LinkedBlockingQueue[(scala.Seq[_], scala.Seq[_], os.Path)],
    LinkedBlockingQueue[Result[scala.Seq[PathRef]]])

  private def visualize0(evaluator: Evaluator, targets: Seq[String], ctx: Ctx, vizWorker: VizWorker,
                         planTasks: Option[List[NamedTask[_]]] = None) = {
    def callVisualizeModule(rs: List[NamedTask[Any]], allRs: List[NamedTask[Any]]) = {
      val (in, out) = vizWorker
      in.put((rs, allRs, ctx.dest))
      out.take()
    }

    RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, targets, multiSelect = true
    ) match {
      case Left(err) => Result.Failure(err)
      case Right(rs) => planTasks match {
        case Some(allRs) => {
          callVisualizeModule(rs, allRs)
        }
        case None => callVisualizeModule(rs, rs)
      }
    }
  }
}
