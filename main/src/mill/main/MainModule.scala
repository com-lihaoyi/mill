package mill.main

import mill.api.*
import mill.define.*
import mill.eval.Evaluator
import mill.exec.ExecutionPaths
import mill.moduledefs.Scaladoc
import mill.define.SelectMode.Separated
import mill.define.SelectMode
import mill.define.Watchable

import java.util.concurrent.LinkedBlockingQueue
import scala.collection.mutable

/**
 * [[mill.define.Module]] containing all the default tasks that Mill provides: [[resolve]],
 * [[show]], [[inspect]], [[plan]], etc.
 */
trait MainModule extends BaseModule {
  protected[mill] val watchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]
  protected[mill] val evalWatchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]
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

    def watch0(w: Watchable): Unit = watchedValues.append(w)

    def evalWatch0(w: Watchable): Unit = evalWatchedValues.append(w)

  }

  /**
   * Show the mill version.
   */
  def version(): Command[String] = Task.Command(exclusive = true) {
    val res = BuildInfo.millVersion
    println(res)
    res
  }

  /**
   * Resolves a mill query string and prints out the tasks it resolves to.
   */
  def resolve(evaluator: Evaluator, targets: String*): Command[List[String]] =
    Task.Command(exclusive = true) {
      val resolved = evaluator.resolveSegments(targets, SelectMode.Multi)

      resolved.map { resolvedSegmentsList =>
        val resolvedStrings = resolvedSegmentsList.map(_.render)
        resolvedStrings.sorted.foreach(println)
        resolvedStrings
      }
    }

  /**
   * Given a set of tasks, prints out the execution plan of what tasks will be
   * executed in what order, without actually executing them.
   */
  def plan(evaluator: Evaluator, targets: String*): Command[Array[String]] =
    Task.Command(exclusive = true) {
      MainModule.plan0(evaluator, targets).map {
        success =>
          val renderedTasks = success.map(_.toString)
          renderedTasks.foreach(println)
          renderedTasks
      }
    }

  /**
   * Prints out some dependency path from the `src` task to the `dest` task.
   *
   * If there are multiple dependency paths between `src` and `dest`, the path
   * chosen is arbitrary.
   */

  def path(
      evaluator: Evaluator,
      @mainargs.arg(positional = true) src: String,
      @mainargs.arg(positional = true) dest: String
  ): Command[List[String]] =
    Task.Command(exclusive = true) {
      evaluator.resolveTasks(List(src, dest), SelectMode.Multi).flatMap {
        case Seq(src1, dest1) =>
          val queue = collection.mutable.Queue[List[Task[?]]](List(src1))
          var found = Option.empty[List[Task[?]]]
          val seen = collection.mutable.Set.empty[Task[?]]
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
            case None => Result.Failure(s"No path found between $src and $dest")
            case Some(list) =>
              val labels = list.collect { case n: NamedTask[_] => n.ctx.segments.render }
              labels.foreach(println)
              Result.Success(labels)
          }

        case _ => ???
      }
    }

  /**
   * Displays metadata about the given task without actually running it.
   */
  def inspect(evaluator: Evaluator, tasks: String*): Command[String] =
    Task.Command(exclusive = true) {
      Inspect.inspect(evaluator, tasks)
    }

  /**
   * Runs a given task and prints the JSON result to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def show(evaluator: Evaluator, targets: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      MainModule.show0(evaluator, targets, Target.log, interp.evalWatch0) { res =>
        res.flatMap(_._2) match {
          case Seq((k, singleValue)) => singleValue
          case multiple => ujson.Obj.from(multiple)
        }
      }
    }

  /**
   * Runs a given task and prints the results as JSON dictionary to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def showNamed(evaluator: Evaluator, targets: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      MainModule.show0(evaluator, targets, Target.log, interp.evalWatch0) { res =>
        ujson.Obj.from(res.flatMap(_._2))
      }
    }

  /**
   * Deletes the given targets from the out directory. Providing no targets
   * will clean everything.
   */
  def clean(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      val rootDir = evaluator.outPath

      val KeepPattern = "(mill-.+)".r.anchored

      def keepPath(path: os.Path) = path.last match {
        case KeepPattern(_) => true
        case _ => false
      }

      val pathsToRemove =
        if (targets.isEmpty)
          Result.Success((os.list(rootDir).filterNot(keepPath), List(mill.define.Segments())))
        else
          evaluator.resolveSegments(targets, SelectMode.Multi).map { ts =>
            val allPaths = ts.flatMap { segments =>
              val evPaths = ExecutionPaths.resolveDestPaths(rootDir, segments)
              val paths = Seq(evPaths.dest, evPaths.meta, evPaths.log)
              val potentialModulePath = rootDir / ExecutionPaths.makeSegmentStrings(segments)
              if (os.exists(potentialModulePath)) {
                // this is either because of some pre-Mill-0.10 files lying around
                // or most likely because the segments denote a module but not a task
                // in which case we want to remove the module and all its sub-modules
                // (If this logic is later found to be too harsh, we could further guard it,
                // to when none of the other paths exists.)
                paths :+ potentialModulePath
              } else paths
            }
            (allPaths, ts)
          }

      (pathsToRemove: @unchecked).map {
        case (paths, allSegments) =>
          for {
            workerSegments <- evaluator.workerCache.keys.toList
            if allSegments.exists(workerSegments.startsWith)
            case (_, Val(closeable: AutoCloseable)) <-
              evaluator.execution.workerCache.remove(workerSegments)
          } {
            closeable.close()
          }

          val existing = paths.filter(p => os.exists(p))
          Target.log.debug(s"Cleaning ${existing.size} paths ...")
          existing.foreach(os.remove.all(_, ignoreErrors = true))
          existing.map(PathRef(_))
      }
    }

  /**
   * Renders the dependencies between the given tasks as a SVG for you to look at
   */
  def visualize(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      VisualizeModule.visualize0(
        evaluator,
        targets,
        Target.ctx(),
        mill.main.VisualizeModule.worker()
      )
    }

  /**
   * Renders the dependencies between the given tasks, and all their dependencies, as a SVG
   */
  def visualizePlan(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      MainModule.plan0(evaluator, targets).flatMap {
        planResults =>
          VisualizeModule.visualize0(
            evaluator,
            targets,
            Target.ctx(),
            mill.main.VisualizeModule.worker(),
            Some(planResults.toList)
          )
      }
    }

  /**
   * Shuts down mill's background server
   */
  def shutdown(): Command[Unit] = Task.Command(exclusive = true) {
    Target.log.info("Shutting down Mill server...")
    Target.ctx().systemExit(0)
    ()
  }

  /**
   * The `init` allows you to quickly generate a starter project.
   *
   * If you run it without arguments, it displays the list of available examples.
   *
   * If you pass one of listed examples, it downloads specified example from mill releases page and extracts it to working directory.
   *
   * If you pass a g8 template, it will generate a project based on a Giter8 template.
   * It prompts you to enter project name and creates a folder with that name.
   * There are lots of templates out there for many frameworks and tools!
   */
  def init(evaluator: Evaluator, args: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      val evaluated =
        if (os.exists(os.pwd / "pom.xml"))
          evaluator.resolveEvaluate(
            Seq("mill.init.InitMavenModule/init") ++ args,
            SelectMode.Separated
          )
        else if (
          os.exists(os.pwd / "build.gradle") ||
          os.exists(os.pwd / "build.gradle.kts") ||
          os.exists(os.pwd / "settings.gradle") ||
          os.exists(os.pwd / "settings.gradle.kts")
        )
          evaluator.resolveEvaluate(
            Seq("mill.init.InitGradleModule/init") ++ args,
            SelectMode.Separated
          )
        else if (args.headOption.exists(_.toLowerCase.endsWith(".g8")))
          evaluator.resolveEvaluate(
            Seq("mill.scalalib.giter8.Giter8Module/init") ++ args,
            SelectMode.Separated
          )
        else
          evaluator.resolveEvaluate(
            Seq("mill.init.InitModule/init") ++ args,
            SelectMode.Separated
          )
      (evaluated: @unchecked) match {
        case Result.Failure(failStr) => throw new Exception(failStr)
        case Result.Success((_, Result.Success(Seq((_, Some((_, jsonableResult))))))) =>
          jsonableResult
        case Result.Success((_, Result.Failure(failStr))) => throw new Exception(failStr)
      }
    }

  /**
   * Commands related to selective execution, where Mill runs tasks selectively
   * depending on what task inputs or implementations changed
   */
  lazy val selective: SelectiveExecutionModule = new SelectiveExecutionModule {}

}

object MainModule {

  private def show0(
      evaluator: Evaluator,
      targets: Seq[String],
      log: Logger,
      watch0: Watchable => Unit
  )(f: Seq[(Any, Option[(Evaluator.TaskName, ujson.Value)])] => ujson.Value)
      : Result[ujson.Value] = {

    // When using `show`, redirect all stdout of the evaluated tasks so the
    // printed JSON is the only thing printed to stdout.
    val redirectLogger = log
      .withOutStream(evaluator.baseLogger.errorStream)
      .asInstanceOf[ColorLogger]

    evaluator.withBaseLogger(redirectLogger)
      .resolveEvaluate(
        targets,
        Separated,
        selectiveExecution = evaluator.selectiveExecution
      ).flatMap {
        case (watched, Result.Failure(err)) =>
          watched.foreach(watch0)
          Result.Failure(err)

        case (watched, Result.Success(res)) =>
          val output = f(res)
          watched.foreach(watch0)
          println(output.render(indent = 2))
          Result.Success(output)
      }
  }

  def plan0(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Result[Array[NamedTask[?]]] = {
    evaluator.resolveTasks(tasks, SelectMode.Multi).map {
      rs =>
        val plan = evaluator.plan(rs)
        plan.sortedGroups.keys().collect { case r: NamedTask[_] => r }.toArray
    }
  }

}
