package mill.main

import java.util.concurrent.LinkedBlockingQueue
import mill.{BuildInfo, T}
import mill.api.{Ctx, Logger, PathRef, Result}
import mill.define.{Command, NamedTask, Segments, Task}
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.resolve.{Resolve, SelectMode}
import mill.resolve.SelectMode.Separated
import mill.util.{PrintLogger, Watchable}
import pprint.{Renderer, Tree, Truncated}

import scala.collection.mutable

object MainModule {

  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: SelectMode
  )(f: List[NamedTask[Any]] => T): Result[T] = {
    Resolve.Tasks.resolve(evaluator.rootModule, targets, selectMode) match {
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }

  private def show0(
      evaluator: Evaluator,
      targets: Seq[String],
      log: Logger,
      watch0: Watchable => Unit
  )(f: Seq[(Any, Option[(RunScript.TaskName, ujson.Value)])] => ujson.Value) = {
    RunScript.evaluateTasksNamed(
      evaluator.withBaseLogger(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        evaluator.baseLogger match {
          case p: PrintLogger => p.withOutStream(p.errorStream)
          case l => l
        }
      ),
      targets,
      Separated
    ) match {
      case Left(err) => Result.Failure(err)
      case Right((watched, Left(err))) =>
        watched.foreach(watch0)
        Result.Failure(err)

      case Right((watched, Right(res))) =>
        val output = f(res)
        watched.foreach(watch0)
        log.outputStream.println(output.render(indent = 2))
        Result.Success(output)
    }
  }
}

/**
 * [[mill.Module]] containing all the default tasks that Mill provides: [[resolve]],
 * [[show]], [[inspect]], [[plan]], etc.
 */
trait MainModule extends mill.Module {
  protected[mill] val watchedValues = mutable.Buffer.empty[Watchable]
  protected[mill] val evalWatchedValues = mutable.Buffer.empty[Watchable]

  object interp {

    def watchValue[T](v0: => T)(implicit fn: sourcecode.FileName, ln: sourcecode.Line): T = {
      val v = v0
      val watchable = Watchable.Value(
        () => v0.hashCode,
        v.hashCode(),
        fn.value + ":" + ln.value
      )
      watchedValues.append(watchable)
      v
    }

    def watch(p: os.Path): os.Path = {
      val watchable = Watchable.Path(PathRef(p))
      watchedValues.append(watchable)
      p
    }

    def watch0(w: Watchable): Unit = {
      watchedValues.append(w)
    }

    def evalWatch0(w: Watchable): Unit = {
      evalWatchedValues.append(w)
    }
  }

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
    val resolved = Resolve.Segments.resolve(
      evaluator.rootModule,
      targets,
      SelectMode.Multi
    )

    resolved match {
      case Left(err) => Result.Failure(err)
      case Right(resolvedSegmentsList) =>
        val resolvedStrings = resolvedSegmentsList.map(_.render)
        resolvedStrings.sorted.foreach(T.log.outputStream.println)
        Result.Success(resolvedStrings)
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
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      targets,
      SelectMode.Multi
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = evaluator.plan(rs)
        Right(sortedGroups.keys().collect { case r: Evaluator.Terminal.Labelled[_] => r }.toArray)
    }
  }

  /**
   * Prints out some dependency path from the `src` task to the `dest` task.
   *
   * If there are multiple dependency paths between `src` and `dest`, the path
   * chosen is arbitrary.
   */
  def path(evaluator: Evaluator, src: String, dest: String): Command[List[String]] = T.command {
    val resolved = Resolve.Tasks.resolve(
      evaluator.rootModule,
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

    def resolveParents(c: Class[_]): Seq[Class[_]] = {
      Seq(c) ++ Option(c.getSuperclass).toSeq.flatMap(resolveParents) ++ c.getInterfaces.flatMap(
        resolveParents
      )
    }
    def pprintTask(t: NamedTask[_], evaluator: Evaluator): Tree.Lazy = {
      val seen = mutable.Set.empty[Task[_]]

      def rec(t: Task[_]): Seq[Segments] = {
        if (seen(t)) Nil // do nothing
        else t match {
          case t: mill.define.Target[_] if evaluator.rootModule.millInternal.targets.contains(t) =>
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
          yield mill.util.Util.cleanupScaladoc(a.value).map("\n    " + _).mkString

      pprint.Tree.Lazy { ctx =>
        val mainMethodSig =
          if (t.asCommand.isEmpty) List()
          else {
            val mainDataOpt = evaluator
              .rootModule
              .millDiscover
              .value
              .get(t.ctx.enclosingCls)
              .flatMap(_.find(_.name == t.ctx.segments.parts.last))

            mainDataOpt match {
              case Some(mainData) if mainData.renderedArgSigs.nonEmpty =>
                val rendered = mainargs.Renderer.formatMainMethodSignature(
                  mainDataOpt.get,
                  leftIndent = 2,
                  totalWidth = 100,
                  leftColWidth = mainargs.Renderer.getLeftColWidth(mainData.renderedArgSigs),
                  docsOnNewLine = false,
                  customName = None,
                  customDoc = None
                )

                // trim first line containing command name, since we already render
                // the command name below with the filename and line num
                val trimmedRendered = rendered
                  .linesIterator
                  .drop(1)
                  .mkString("\n")

                List("\n", trimmedRendered, "\n")

              case _ => List()
            }
          }

        Iterator(
          ctx.applyPrefixColor(t.toString).toString,
          "(",
          // handle both Windows or Unix separators
          t.ctx.fileName.split('/').last.split('\\').last,
          ":",
          t.ctx.lineNum.toString,
          ")",
          allDocs.mkString("\n"),
          "\n"
        ) ++
          mainMethodSig.iterator ++
          Iterator(
            "\n",
            ctx.applyPrefixColor("Inputs").toString,
            ":"
          ) ++ t.inputs.distinct.iterator.flatMap(rec).map("\n    " + _.render)
      }
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
        val sb = new StringBuilder()
        for { str <- truncated ++ Iterator("\n") } sb.append(str)
        sb.toString()
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
    MainModule.show0(evaluator, targets, T.log, interp.evalWatch0) { res =>
      res.flatMap(_._2).map(_._2) match {
        case Seq(single) => single
        case multiple => multiple
      }
    }
  }

  /**
   * Runs a given task and prints the results as JSON dictionary to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def showNamed(evaluator: Evaluator, targets: String*): Command[ujson.Value] = T.command {
    MainModule.show0(evaluator, targets, T.log, interp.evalWatch0) { res =>
      ujson.Obj.from(res.flatMap(_._2))
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
        mill.resolve.Resolve.Segments.resolve(
          evaluator.rootModule,
          targets,
          SelectMode.Multi
        ).map { ts =>
          ts.flatMap { segments =>
            val evPaths = EvaluatorPaths.resolveDestPaths(rootDir, segments)
            val paths = Seq(evPaths.dest, evPaths.meta, evPaths.log)
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

  /**
   * The `init` command generates a project based on a Giter8 template. It
   * prompts you to enter project name and creates a folder with that name.
   * You can use it to quickly generate a starter project. There are lots of
   * templates out there for many frameworks and tools!
   */
  def init(evaluator: Evaluator, args: String*): Command[Unit] = T.command {
    RunScript.evaluateTasksNamed(
      evaluator,
      Seq("mill.scalalib.giter8.Giter8Module/init") ++ args,
      SelectMode.Separated
    )

    ()
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

    Resolve.Tasks.resolve(
      evaluator.rootModule,
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
