package mill.main

import mill.api.{Ctx, _}
import mill.define.{BaseModule0, Command, NamedTask, Segments, Target, Task, _}
import mill.eval.{Evaluator, EvaluatorPaths, Terminal}
import mill.moduledefs.Scaladoc
import mill.resolve.SelectMode.Separated
import mill.resolve.{Resolve, SelectMode}
import mill.util.{Util, Watchable}
import pprint.{Renderer, Tree, Truncated}

import java.util.concurrent.LinkedBlockingQueue
import scala.collection.mutable
import scala.reflect.NameTransformer.decode

object MainModule {

  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: SelectMode,
      resolveToModuleTasks: Boolean = false
  )(f: List[NamedTask[Any]] => T): Result[T] = {
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      targets,
      selectMode,
      resolveToModuleTasks = resolveToModuleTasks
    ) match {
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }

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
  )(f: Seq[(Any, Option[(RunScript.TaskName, ujson.Value)])] => ujson.Value)
      : Result[ujson.Value] = {

    // When using `show`, redirect all stdout of the evaluated tasks so the
    // printed JSON is the only thing printed to stdout.
    val redirectLogger = log
      .withOutStream(evaluator.baseLogger.errorStream)
      .asInstanceOf[mill.util.ColorLogger]

    RunScript.evaluateTasksNamed(
      evaluator.withBaseLogger(redirectLogger),
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
        println(output.render(indent = 2))
        Result.Success(output)
    }
  }
}

/**
 * [[mill.define.Module]] containing all the default tasks that Mill provides: [[resolve]],
 * [[show]], [[inspect]], [[plan]], etc.
 */
trait MainModule extends BaseModule0 {

  object interp extends Interp

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
      val resolved = Resolve.Segments.resolve(
        evaluator.rootModule,
        targets,
        SelectMode.Multi
      )

      resolved match {
        case Left(err) => Result.Failure(err)
        case Right(resolvedSegmentsList) =>
          val resolvedStrings = resolvedSegmentsList.map(_.render)
          resolvedStrings.sorted.foreach(println)
          Result.Success(resolvedStrings)
      }
    }

  /**
   * Given a set of tasks, prints out the execution plan of what tasks will be
   * executed in what order, without actually executing them.
   */
  def plan(evaluator: Evaluator, targets: String*): Command[Array[String]] =
    Task.Command(exclusive = true) {
      plan0(evaluator, targets) match {
        case Left(err) => Result.Failure(err)
        case Right(success) =>
          val renderedTasks = success.map(_.segments.render)
          renderedTasks.foreach(println)
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
        Right(sortedGroups.keys().collect { case r: Terminal.Labelled[_] => r }.toArray)
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

              labels.foreach(println)
              Result.Success(labels)
          }
      }
    }

  /**
   * Displays metadata about the given task without actually running it.
   */
  def inspect(evaluator: Evaluator, tasks: String*): Command[String] =
    Task.Command(exclusive = true) {
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
            case t: mill.define.Target[_]
                if evaluator.rootModule.millInternal.targets.contains(t) =>
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
                .flatMap(_._2.find(_.name == t.ctx.segments.parts.last))
                .headOption

              mainDataOpt match {
                case Some(mainData) if mainData.renderedArgSigs.nonEmpty =>
                  val rendered = mainargs.Renderer.formatMainMethodSignature(
                    mainDataOpt.get,
                    leftIndent = 2,
                    totalWidth = 100,
                    leftColWidth = mainargs.Renderer.getLeftColWidth(mainData.renderedArgSigs),
                    docsOnNewLine = false,
                    customName = None,
                    customDoc = None,
                    sorted = true
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
            ) ++ t.inputs.iterator.flatMap(rec).map("\n    " + _.render).distinct
        }
      }

      def pprintModule(t: ModuleTask[_], evaluator: Evaluator): Tree.Lazy = {
        val cls = t.module.getClass
        val annotation = cls.getAnnotation(classOf[Scaladoc])
        val scaladocOpt = Option.when(annotation != null)(
          Util.cleanupScaladoc(annotation.value).map("\n    " + _).mkString
        )
        val fileName = t.ctx.fileName.split('/').last.split('\\').last
        val parents = cls.getInterfaces ++ Option(cls.getSuperclass).toSeq
        val inheritedModules =
          parents.distinct.filter(classOf[Module].isAssignableFrom(_)).map(_.getSimpleName)
        val moduleDepsOpt = cls.getMethods.find(m => decode(m.getName) == "moduleDeps").map(
          _.invoke(t.module).asInstanceOf[Seq[Module]]
        ).filter(_.nonEmpty)
        val defaultTaskOpt = t.module match {
          case taskMod: TaskModule => Some(s"${t.module}.${taskMod.defaultCommandName()}")
          case _ => None
        }
        val methodMap = evaluator.rootModule.millDiscover.value
        val tasksOpt = methodMap.get(cls).map {
          case (_, _, tasks) => tasks.map(task => s"${t.module}.$task")
        }
        pprint.Tree.Lazy { ctx =>
          Iterator(
            ctx.applyPrefixColor(t.module.toString).toString,
            s"($fileName:${t.ctx.lineNum})"
          ) ++ Iterator(scaladocOpt).flatten ++ Iterator(
            "\n\n",
            ctx.applyPrefixColor("Inherited Modules").toString,
            ": ",
            inheritedModules.mkString(", ")
          ) ++ moduleDepsOpt.fold(Iterator.empty[String])(deps =>
            Iterator(
              "\n\n",
              ctx.applyPrefixColor("Module Dependencies").toString,
              ": ",
              deps.mkString(", ")
            )
          ) ++ defaultTaskOpt.fold(Iterator.empty[String])(task =>
            Iterator("\n\n", ctx.applyPrefixColor("Default Task").toString, ": ", task)
          ) ++ tasksOpt.fold(Iterator.empty[String])(tasks =>
            Iterator(
              "\n\n",
              ctx.applyPrefixColor("Tasks").toString,
              ": ",
              tasks.mkString(", ")
            )
          )
        }
      }

      MainModule.resolveTasks(evaluator, tasks, SelectMode.Multi, resolveToModuleTasks = true) {
        tasks =>
          val output = (for {
            task <- tasks
            tree = task match {
              case t: ModuleTask[_] => pprintModule(t, evaluator)
              case t => pprintTask(t, evaluator)
            }
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
          println(output)
          fansi.Str(output).plainText
      }
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
          Right((os.list(rootDir).filterNot(keepPath), List(mill.define.Segments())))
        else
          mill.resolve.Resolve.Segments.resolve(
            evaluator.rootModule,
            targets,
            SelectMode.Multi
          ).map { ts =>
            val allPaths = ts.flatMap { segments =>
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
            (allPaths, ts)
          }

      pathsToRemove match {
        case Left(err) =>
          Result.Failure(err)
        case Right((paths, allSegments)) =>
          for {
            workerSegments <- evaluator.workerCache.keys.toList
            if allSegments.exists(workerSegments.startsWith)
            case (_, Val(closeable: AutoCloseable)) <-
              evaluator.mutableWorkerCache.remove(workerSegments)
          } {
            closeable.close()
          }

          val existing = paths.filter(p => os.exists(p))
          Target.log.debug(s"Cleaning ${existing.size} paths ...")
          existing.foreach(os.remove.all)
          Result.Success(existing.map(PathRef(_)))
      }
    }

  /**
   * Renders the dependencies between the given tasks as a SVG for you to look at
   */
  def visualize(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      visualize0(evaluator, targets, Target.ctx(), mill.main.VisualizeModule.worker())
    }

  /**
   * Renders the dependencies between the given tasks, and all their dependencies, as a SVG
   */
  def visualizePlan(evaluator: Evaluator, targets: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      plan0(evaluator, targets) match {
        case Left(err) => Result.Failure(err)
        case Right(planResults) => visualize0(
            evaluator,
            targets,
            Target.ctx(),
            mill.main.VisualizeModule.worker(),
            Some(planResults.toList.map(_.task))
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
        if (args.headOption.exists(_.toLowerCase.endsWith(".g8")))
          RunScript.evaluateTasksNamed(
            evaluator,
            Seq("mill.scalalib.giter8.Giter8Module/init") ++ args,
            SelectMode.Separated
          )
        else
          RunScript.evaluateTasksNamed(
            evaluator,
            Seq("mill.init.InitModule/init") ++ args,
            SelectMode.Separated
          )
      evaluated match {
        case Left(failStr) => throw new Exception(failStr)
        case Right((_, Right(Seq((_, Some((_, jsonableResult))))))) => jsonableResult
        case Right((_, Left(failStr))) => throw new Exception(failStr)
      }
    }

  private type VizWorker = (
      LinkedBlockingQueue[(scala.Seq[NamedTask[Any]], scala.Seq[NamedTask[Any]], os.Path)],
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
        tasks: List[NamedTask[Any]],
        transitiveTasks: List[NamedTask[Any]]
    ): Result[Seq[PathRef]] = {
      val (in, out) = vizWorker
      in.put((tasks, transitiveTasks, ctx.dest))
      val res = out.take()
      res.map { v =>
        println(upickle.default.write(v.map(_.path.toString()), indent = 2))
        v
      }
    }

    Resolve.Tasks.resolve(
      evaluator.rootModule,
      targets,
      SelectMode.Multi
    ) match {
      case Left(err) => Result.Failure(err)
      case Right(rs) => planTasks match {
          case Some(allRs) => callVisualizeModule(rs, allRs)
          case None => callVisualizeModule(rs, rs)
        }
    }
  }
}
