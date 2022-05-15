package mill.main

import mainargs.TokensReader

import java.util.concurrent.LinkedBlockingQueue
import mill.{BuildInfo, T}
import mill.api.{Ctx, PathRef, Result, internal}
import mill.define.{Command, NamedTask, Task}
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.{PrintLogger, Watched}
import mill.define.SelectMode
import pprint.{Renderer, Truncated}
import ujson.Value

import scala.util.chaining.scalaUtilChainingOps

object MainModule {
  @deprecated(
    "use resolveTasks(Evaluator, Seq[String], SelectMode) instead",
    "mill after 0.10.0-M3"
  )
  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      multiSelect: Boolean
  )(f: List[NamedTask[Any]] => T): Result[T] =
    resolveTasks(evaluator, targets, if (multiSelect) SelectMode.Multi else SelectMode.Single)(f)

  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: SelectMode
  )(f: List[NamedTask[Any]] => T): Result[T] = {
    RunScript.resolveTasks(mill.main.ResolveTasks, evaluator, targets, selectMode) match {
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }

  @deprecated(
    "use evaluateTasks(Evaluator, Seq[String], SelectMode) instead",
    "mill after 0.10.0-M3"
  )
  def evaluateTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      multiSelect: Boolean
  )(f: Seq[(Any, Option[ujson.Value])] => T): Result[Watched[Unit]] =
    evaluateTasks(evaluator, targets, if (multiSelect) SelectMode.Multi else SelectMode.Single)(f)

  def evaluateTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: SelectMode
  )(f: Seq[(Any, Option[ujson.Value])] => T): Result[Watched[Unit]] = {
    RunScript.evaluateTasks(evaluator, targets, selectMode) match {
      case Left(err) => Result.Failure(err)
      case Right((watched, Left(err))) => Result.Failure(err, Some(Watched((), watched)))
      case Right((watched, Right(res))) =>
        f(res)
        Result.Success(Watched((), watched))
    }
  }

  @internal
  def evaluateTasksNamed[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: SelectMode
  )(f: Seq[(Any, Option[(RunScript.TaskName, ujson.Value)])] => T): Result[Watched[Option[T]]] = {
    RunScript.evaluateTasksNamed(evaluator, targets, selectMode) match {
      case Left(err) => Result.Failure(err)
      case Right((watched, Left(err))) => Result.Failure(err, Some(Watched(None, watched)))
      case Right((watched, Right(res))) =>
        val fRes = f(res)
        Result.Success(Watched(Some(fRes), watched))
    }
  }
}

trait MainModule extends mill.Module {

  implicit def millDiscover: mill.define.Discover[_]
  // TODO: change return type to mainargs.TokensReader[Tasks[T]] when in 0.11 milestone
  implicit def millScoptTasksReads[T]: Tasks.Scopt[T] = new mill.main.Tasks.Scopt[T]()
  // TODO: change return type to mainargs.TokensReader[Evaluator] when in 0.11 milestone
  implicit def millScoptEvaluatorReads[T]: EvaluatorScopt[T] = new mill.main.EvaluatorScopt[T]()
  implicit def taskTokensReader[T](implicit
      tokensReaderOfT: TokensReader[T]
  ): TokensReader[Task[T]] = new TaskScopt[T](tokensReaderOfT)

  /**
   * Show the mill version.
   */
  def version(): Command[String] = mill.T.command {
    val res = BuildInfo.millVersion
    println(res)
    res
  }

  /**
   * Resolves a mill query string and prints out the tasks it resolves to.
   */
  def resolve(evaluator: Evaluator, targets: String*): Command[List[String]] = T.command {
    val resolved: Either[String, List[String]] = RunScript.resolveTasks(
      mill.main.ResolveMetadata,
      evaluator,
      targets,
      SelectMode.Multi
    )

    resolved match {
      case Left(err) => Result.Failure(err)
      case Right(rs) =>
        rs.sorted.foreach(T.log.outputStream.println)
        Result.Success(rs)
    }
  }

  /**
   * Given a set of tasks, prints out the execution plan of what tasks will be
   * executed in what order, without actually executing them.
   */
  def plan(evaluator: Evaluator, targets: String*): Command[Array[String]] = T.command {
    plan0(evaluator, targets) match {
      case Left(err) => Result.Failure(err)
      case Right(success) =>
        val renderedTasks = success.map(_.segments.render)
        renderedTasks.foreach(T.log.outputStream.println)
        Result.Success(renderedTasks)
    }
  }

  private def plan0(evaluator: Evaluator, targets: Seq[String]) = {
    RunScript.resolveTasks(
      mill.main.ResolveTasks,
      evaluator,
      targets,
      SelectMode.Multi
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = Evaluator.plan(rs)
        Right(sortedGroups.keys().collect { case Right(r) => r }.toArray)
    }
  }

  /**
   * Prints out some dependency path from the `src` task to the `dest` task.
   *
   * If there are multiple dependency paths between `src` and `dest`, the path
   * chosen is arbitrary.
   */
  def path(evaluator: Evaluator, src: String, dest: String): Command[List[String]] = T.command {
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveTasks,
      evaluator,
      List(src, dest),
      SelectMode.Multi
    )

    resolved match {
      case Left(err) => Result.Failure(err)
      case Right(Seq(src1, dest1)) =>
        val queue = collection.mutable.Queue[List[Task[_]]](List(src1))
        var found = Option.empty[List[Task[_]]]
        val seen = collection.mutable.Set.empty[Task[_]]
        while (queue.nonEmpty && found.isEmpty) {
          val current = queue.dequeue()
          if (current.head == dest1) found = Some(current)
          else {
            for {
              next <- current.head.inputs
              if !seen.contains(next)
            } {
              seen.add(next)
              queue.enqueue(next :: current)
            }
          }
        }
        found match {
          case None =>
            Result.Failure(s"No path found between $src and $dest")
          case Some(list) =>
            val labels = list
              .collect { case n: NamedTask[_] => n.ctx.segments.render }

            labels.foreach(mill.T.log.outputStream.println(_))

            Result.Success(labels)
        }
    }
  }

  /**
   * Displays metadata about the given task without actually running it.
   */
  def inspect(evaluator: Evaluator, targets: String*): Command[String] = mill.T.command {
    MainModule.resolveTasks(evaluator, targets, SelectMode.Multi) { tasks =>
      val output = (for {
        task <- tasks
        tree = ReplApplyHandler.pprintTask(task, evaluator)
        defaults = pprint.PPrinter()
        renderer = new Renderer(
          defaults.defaultWidth,
          defaults.colorApplyPrefix,
          defaults.colorLiteral,
          defaults.defaultIndent
        )
        rendered = renderer.rec(tree, 0, 0).iter
        truncated = new Truncated(rendered, defaults.defaultWidth, defaults.defaultHeight)
      } yield {
        new StringBuilder().tap { sb =>
          for { str <- truncated ++ Iterator("\n") } sb.append(str)
        }.toString()
      }).mkString("\n")
      T.log.outputStream.println(output)
      fansi.Str(output).plainText
    }
  }

  /**
   * Runs multiple tasks in a single call.
   * For compatibility reasons, the tasks are executed single-threaded.
   */
  @deprecated(
    "Use the + separator, wildcards, or brace-expansion to specify multiple targets.",
    "mill after 0.10.0-M3"
  )
  def all(evaluator: Evaluator, targets: String*) = mill.T.command {
    MainModule.evaluateTasks(
      evaluator =
        if (evaluator.effectiveThreadCount > 1) evaluator.withThreadCount(Some(1))
        else evaluator,
      targets = targets,
      SelectMode.Multi
    ) { res =>
      res.flatMap(_._2)
    }
  }

  /**
   * Runs multiple tasks in a single call in parallel.
   */
  @deprecated(
    "Use the + separator, wildcards, or brace-expansion to specify multiple targets.",
    "mill after 0.10.0-M3"
  )
  def par(evaluator: Evaluator, targets: String*) = T.command {
    MainModule.evaluateTasks(
      evaluator = evaluator,
      targets = targets,
      SelectMode.Multi
    ) { res =>
      res.flatMap(_._2)
    }
  }

  /**
   * Runs a given task and prints the JSON result to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def show(evaluator: Evaluator, targets: String*): Command[Value] = T.command {
    MainModule.evaluateTasksNamed(
      evaluator.withBaseLogger(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        evaluator.baseLogger match {
          case PrintLogger(c1, d, c2, c3, _, i, e, in, de, uc) =>
            PrintLogger(c1, d, c2, c3, e, i, e, in, de, uc)
          case l => l
        }
      ),
      targets,
      SelectMode.Separated
    ) { res: Seq[(Any, Option[(String, ujson.Value)])] =>
      val jsons = res.flatMap(_._2).map(_._2)
      val output: ujson.Value =
        if (jsons.size == 1) jsons.head
        else { ujson.Arr.from(jsons) }
      T.log.outputStream.println(output.render(indent = 2))
      output
    }.map { res: Watched[Option[Value]] =>
      res.value.getOrElse(ujson.Null)
    }
  }

  /**
   * Runs a given task and prints the results as JSON dictionary to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def showNamed(evaluator: Evaluator, targets: String*): Command[Value] = T.command {
    MainModule.evaluateTasksNamed(
      evaluator.withBaseLogger(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        evaluator.baseLogger match {
          case PrintLogger(c1, d, c2, c3, _, i, e, in, de, uc) =>
            PrintLogger(c1, d, c2, c3, e, i, e, in, de, uc)
          case l => l
        }
      ),
      targets,
      SelectMode.Separated
    ) { res: Seq[(Any, Option[(String, ujson.Value)])] =>
      val nameAndJson = res.flatMap(_._2)
      val output: ujson.Value = ujson.Obj.from(nameAndJson)
      T.log.outputStream.println(output.render(indent = 2))
      output
    }.map { res: Watched[Option[Value]] =>
      res.value.getOrElse(ujson.Null)
    }
  }

  /**
   * Deletes the given targets from the out directory. Providing no targets
   * will clean everything.
   */
  def clean(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] = T.command {
    if (evaluator.effectiveThreadCount > 1) {
      evaluator.baseLogger.error(
        "The clean target in parallel mode might result in unexpected effects"
      )
    }

    val rootDir = evaluator.outPath

    val KeepPattern = "(mill-.+)".r.anchored

    def keepPath(path: os.Path) = path.last match {
      case KeepPattern(_) => true
      case _ => false
    }

    val pathsToRemove =
      if (targets.isEmpty)
        Right(os.list(rootDir).filterNot(keepPath))
      else
        RunScript.resolveTasks(
          mill.main.ResolveSegments,
          evaluator,
          targets,
          SelectMode.Multi
        ).map(
          _.flatMap { segments =>
            val paths = EvaluatorPaths.resolveDestPaths(rootDir, segments)
            Seq(paths.dest, paths.meta, paths.log)
          }
        )

    pathsToRemove match {
      case Left(err) =>
        Result.Failure(err)
      case Right(paths) =>
        T.log.debug(s"Cleaning ${paths.size} paths ...")
        paths.foreach(os.remove.all)
        Result.Success(paths.map(PathRef(_)))
    }
  }

  /**
   * Renders the dependencies between the given tasks as a SVG for you to look at
   */
  def visualize(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] = T.command {
    visualize0(evaluator, targets, T.ctx(), mill.main.VisualizeModule.worker())
  }

  /**
   * Renders the dependencies between the given tasks, and all their dependencies, as a SVG
   */
  def visualizePlan(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] = T.command {
    plan0(evaluator, targets) match {
      case Left(err) => Result.Failure(err)
      case Right(planResults) => visualize0(
          evaluator,
          targets,
          T.ctx(),
          mill.main.VisualizeModule.worker(),
          Some(planResults.toList.map(_.task))
        )
    }
  }

  /**
   * Shuts down mill's background server
   */
  def shutdown(): Command[Unit] = T.command {
    T.log.info("Shutting down Mill server...")
    System.exit(0)
  }

  def `new`(args: String*): Command[Int] = T.command {
    T.log.info("Creating a new project...")
    giter8.Giter8.run(args.toArray)
  }

  private type VizWorker = (
      LinkedBlockingQueue[(scala.Seq[_], scala.Seq[_], os.Path)],
      LinkedBlockingQueue[Result[scala.Seq[PathRef]]]
  )

  private def visualize0(
      evaluator: Evaluator,
      targets: Seq[String],
      ctx: Ctx,
      vizWorker: VizWorker,
      planTasks: Option[List[NamedTask[_]]] = None
  ): Result[Seq[PathRef]] = {
    def callVisualizeModule(
        rs: List[NamedTask[Any]],
        allRs: List[NamedTask[Any]]
    ): Result[Seq[PathRef]] = {
      val (in, out) = vizWorker
      in.put((rs, allRs, ctx.dest))
      out.take()
    }

    RunScript.resolveTasks(
      mill.main.ResolveTasks,
      evaluator,
      targets,
      SelectMode.Multi
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
