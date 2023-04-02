package mill.main

import mainargs.TokensReader

import java.util.concurrent.LinkedBlockingQueue
import mill.{BuildInfo, T}
import mill.api.{Ctx, PathRef, Result, internal}
import mill.define.{Command, Segments, SelectMode, Target, TargetImpl, Task}
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.{PrintLogger, Watched}
import pprint.{Renderer, Tree, Truncated}
import ujson.Value

import scala.collection.mutable
import scala.util.chaining.scalaUtilChainingOps

object MainModule {

  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: SelectMode
  )(f: List[Target[Any]] => T): Result[T] = {
    RunScript.resolveTasks(mill.main.ResolveTasks, evaluator, targets, selectMode) match {
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }

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
              .collect { case n: Target[_] => n.ctx.segments.render }

            labels.foreach(mill.T.log.outputStream.println(_))

            Result.Success(labels)
        }
    }
  }

  /**
   * Displays metadata about the given task without actually running it.
   */
  def inspect(evaluator: Evaluator, targets: String*): Command[String] = mill.T.command {

    def resolveParents(c: Class[_]): Seq[Class[_]] = {
      Seq(c) ++ Option(c.getSuperclass).toSeq.flatMap(resolveParents) ++ c.getInterfaces.flatMap(
        resolveParents
      )
    }
    def pprintTask(t: Target[_], evaluator: Evaluator): Tree.Lazy = {
      val seen = mutable.Set.empty[Task[_]]

      def rec(t: Task[_]): Seq[Segments] = {
        if (seen(t)) Nil // do nothing
        else t match {
          case t: TargetImpl[_] if evaluator.rootModule.millInternal.targets.contains(t) =>
            Seq(t.ctx.segments)
          case _ =>
            seen.add(t)
            t.inputs.flatMap(rec)
        }
      }

      val annots = for {
        c <- resolveParents(t.ctx.enclosingCls)
        m <- c.getMethods
        if m.getName == t.ctx.segment.pathSegments.head
        a = m.getAnnotation(classOf[mill.moduledefs.Scaladoc])
        if a != null
      } yield a

      val allDocs =
        for (a <- annots.distinct)
          yield mill.modules.Util.cleanupScaladoc(a.value).map("\n    " + _).mkString

      pprint.Tree.Lazy(ctx =>
        Iterator(
          ctx.applyPrefixColor(t.toString).toString,
          "(",
          t.ctx.fileName.split('/').last,
          ":",
          t.ctx.lineNum.toString,
          ")",
          allDocs.mkString("\n"),
          "\n",
          "\n",
          ctx.applyPrefixColor("Inputs").toString,
          ":"
        ) ++ t.inputs.distinct.iterator.flatMap(rec).map("\n    " + _.render)
      )
    }

    MainModule.resolveTasks(evaluator, targets, SelectMode.Multi) { tasks =>
      val output = (for {
        task <- tasks
        tree = pprintTask(task, evaluator)
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
   * Runs a given task and prints the JSON result to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def show(evaluator: Evaluator, targets: String*): Command[ujson.Value] = T.command {
    MainModule.evaluateTasksNamed(
      evaluator.withBaseLogger(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        evaluator.baseLogger match {
          case p: PrintLogger => p.withOutStream(p.errStream)
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
  def showNamed(evaluator: Evaluator, targets: String*): Command[ujson.Value] = T.command {
    MainModule.evaluateTasksNamed(
      evaluator.withBaseLogger(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        evaluator.baseLogger match {
          case p: PrintLogger => p.withOutStream(outStream = p.errStream)
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
        ).map { ts =>
          ts.flatMap { segments =>
            val evPpaths = EvaluatorPaths.resolveDestPaths(rootDir, segments)
            val paths = Seq(evPpaths.dest, evPpaths.meta, evPpaths.log)
            val potentialModulePath = rootDir / EvaluatorPaths.makeSegmentStrings(segments)
            if (os.exists(potentialModulePath)) {
              // this is either because of some pre-Mill-0.10 files lying around
              // or most likely because the segments denote a module but not a task
              // in which case we want to remove the module and all its sub-modules
              // (If this logic is later found to be to harsh, we could further guard it,
              // to when non of the other paths exists.)
              paths :+ potentialModulePath
            } else paths
          }
        }

    pathsToRemove match {
      case Left(err) =>
        Result.Failure(err)
      case Right(paths) =>
        val existing = paths.filter(p => os.exists(p))
        T.log.debug(s"Cleaning ${existing.size} paths ...")
        existing.foreach(os.remove.all)
        Result.Success(existing.map(PathRef(_)))
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

  def init(evaluator: Evaluator, args: String*): Command[Unit] = T.command {
    MainModule.evaluateTasks(
      evaluator,
      Seq("mill.scalalib.giter8.Giter8Module/init") ++ args,
      selectMode = SelectMode.Single
    )(identity).map(_.value)
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
      planTasks: Option[List[Target[_]]] = None
  ): Result[Seq[PathRef]] = {
    def callVisualizeModule(
        rs: List[Target[Any]],
        allRs: List[Target[Any]]
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
