package mill.main

import mill.api._
import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths, Terminal}
import mill.moduledefs.Scaladoc
import mill.resolve.{Resolve, SelectMode}
import mill.util.{Util, Watchable}
import pprint.{Renderer, Tree, Truncated}

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.{nowarn, tailrec}
import scala.collection.mutable
import scala.reflect.NameTransformer.decode

object MainModule {

  @nowarn("cat=deprecation")
  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: mill.define.SelectMode,
      resolveToModuleTasks: Boolean
  )(f: List[NamedTask[Any]] => T): Result[T] = resolveTasks(
    evaluator,
    targets,
    selectMode match {
      case mill.define.SelectMode.Multi => SelectMode.Multi
      case mill.define.SelectMode.Separated => SelectMode.Separated
    },
    resolveToModuleTasks
  )(f)

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

  @nowarn("cat=deprecation")
  def resolveTasks[T](
      evaluator: Evaluator,
      targets: Seq[String],
      selectMode: mill.define.SelectMode
  )(f: List[NamedTask[Any]] => T): Result[T] = resolveTasks(
    evaluator,
    targets,
    selectMode match {
      case mill.define.SelectMode.Multi => SelectMode.Multi
      case mill.define.SelectMode.Separated => SelectMode.Separated
    }
  )(f)

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
      SelectMode.Separated,
      selectiveExecution = evaluator.selectiveExecution
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

  def plan0(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Either[String, Array[Terminal.Labelled[_]]] = {
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      tasks,
      SelectMode.Multi
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = evaluator.plan(rs)
        Right(sortedGroups.keys().collect { case r: Terminal.Labelled[_] => r }.toArray)
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
      MainModule.plan0(evaluator, targets) match {
        case Left(err) => Result.Failure(err)
        case Right(success) =>
          val renderedTasks = success.map(_.segments.render)
          renderedTasks.foreach(println)
          Result.Success(renderedTasks)
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

  private lazy val inspectItemIndent = "    "

  /**
   * Displays metadata about the given task without actually running it.
   */
  def inspect(evaluator: Evaluator, tasks: String*): Command[String] =
    Task.Command(exclusive = true) {

      /** Find a parent classes of the given class queue. */
      @tailrec
      def resolveParents(queue: List[Class[_]], seen: Seq[Class[_]] = Seq()): Seq[Class[_]] = {
        queue match {
          case Nil => seen
          case cand :: rest if seen.contains(cand) => resolveParents(rest, seen)
          case cand :: rest =>
            val sups = Option(cand.getSuperclass).toList ++ cand.getInterfaces.toList
            resolveParents(sups ::: rest, seen ++ Seq(cand))
        }
      }

      def renderFileName(t: NamedTask[_]) = {
        // handle both Windows or Unix separators
        val fullFileName = t.ctx.fileName.replaceAll(raw"\\", "/")
        val basePath = BuildCtx.workspaceRoot.toString().replaceAll(raw"\\", "/") + "/"
        val name =
          if (fullFileName.startsWith(basePath)) {
            fullFileName.drop(basePath.length)
          } else {
            fullFileName.split('/').last
          }
        s"${name}:${t.ctx.lineNum}"
      }

      def pprintTask(t: NamedTask[_], evaluator: Evaluator): Tree.Lazy = {
        val seen = mutable.Set.empty[Task[_]]

        def rec(t: Task[_]): Seq[Segments] = {
          if (seen(t)) Nil // do nothing
          else t match {
            case t: mill.define.Task.Simple[_]
                if evaluator.rootModule.moduleInternal.targets.contains(t) =>
              Seq(t.ctx.segments)
            case _ =>
              seen.add(t)
              t.inputs.flatMap(rec)
          }
        }

        val annots = for {
          c <- resolveParents(List(t.ctx.enclosingCls))
          m <- c.getMethods
          if m.getName == t.ctx.segment.pathSegments.head
          a = m.getAnnotation(classOf[mill.moduledefs.Scaladoc])
          if a != null
        } yield a

        val allDocs =
          for (a <- annots.distinct)
            yield Util.cleanupScaladoc(a.value).map("\n" + inspectItemIndent + _).mkString

        pprint.Tree.Lazy { ctx =>
          val mainMethodSig =
            if (t.asCommand.isEmpty) List()
            else {
              val mainDataOpt = evaluator
                .rootModule
                .millDiscover
                .value
                .get(t.ctx.enclosingCls)
                .flatMap(_._2.find(_.name == t.ctx.segments.last.value))
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
                    sorted = true,
                    nameMapper = mainargs.Util.kebabCaseNameMapper
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
            renderFileName(t),
            ")",
            allDocs.mkString("\n"),
            "\n"
          ) ++
            mainMethodSig.iterator ++
            Iterator(
              "\n",
              ctx.applyPrefixColor("Inputs").toString,
              ":"
            ) ++ t.inputs.iterator.flatMap(rec).map("\n" + inspectItemIndent + _.render).distinct
        }
      }

      def pprintModule(t: ModuleTask[_], evaluator: Evaluator): Tree.Lazy = {
        val cls = t.module.getClass
        val annotation = cls.getAnnotation(classOf[Scaladoc])
        val scaladocOpt = Option(annotation).map(annotation =>
          Util.cleanupScaladoc(annotation.value).map("\n" + inspectItemIndent + _).mkString
        )

        def parentFilter(parent: Class[_]) =
          classOf[Module].isAssignableFrom(parent) && classOf[Module] != parent

        val parents = (Option(cls.getSuperclass).toSeq ++ cls.getInterfaces).distinct

        val inheritedModules = parents.filter(parentFilter)

        val allInheritedModules = Option.when(Target.log.debugEnabled)(
          resolveParents(parents.toList)
            .filter(parentFilter)
            .filterNot(inheritedModules.contains)
        ).toSeq.flatten

        def getModuleDeps(methodName: String): Seq[Module] = cls
          .getMethods
          .find(m => decode(m.getName) == methodName)
          .toSeq
          .map(_.invoke(t.module).asInstanceOf[Seq[Module]])
          .flatten

        val javaModuleDeps = getModuleDeps("moduleDeps")
        val javaCompileModuleDeps = getModuleDeps("compileModuleDeps")
        val javaRunModuleDeps = getModuleDeps("runModuleDeps")
        val hasModuleDeps =
          javaModuleDeps.nonEmpty || javaCompileModuleDeps.nonEmpty || javaRunModuleDeps.nonEmpty

        val defaultTaskOpt = t.module match {
          case taskMod: TaskModule => Some(s"${t.module}.${taskMod.defaultCommandName()}")
          case _ => None
        }

        val methodMap = evaluator.rootModule.millDiscover.value
        val tasks = methodMap.get(cls).map {
          case (_, _, tasks) => tasks.map(task => s"${t.module}.$task")
        }.toSeq.flatten
        pprint.Tree.Lazy { ctx =>
          Iterator(
            // module name(module/file:line)
            Iterator(
              ctx.applyPrefixColor(t.module.toString).toString,
              s"(${renderFileName(t)})"
            ),
            // Scaladoc
            Iterator(scaladocOpt).flatten,
            // Inherited Modules:
            Iterator(
              "\n\n",
              ctx.applyPrefixColor("Inherited Modules").toString,
              ":"
            ),
            inheritedModules.map("\n" + inspectItemIndent + _.getName),
            // Indirect Inherited Modules:
            if (allInheritedModules.isEmpty) Iterator.empty[String]
            else Iterator(
              "\n\n",
              ctx.applyPrefixColor("Indirect Inherited Modules").toString,
              ":\n",
              inspectItemIndent,
              allInheritedModules.map(_.getName).mkString("\n" + inspectItemIndent)
            ),
            // Module Dependencies: (JavaModule)
            if (hasModuleDeps) Iterator(
              "\n\n",
              ctx.applyPrefixColor("Module Dependencies").toString,
              ":"
            )
            else Iterator.empty[String],
            javaModuleDeps.map("\n" + inspectItemIndent + _.toString),
            javaCompileModuleDeps.map("\n" + inspectItemIndent + _.toString + " (compile)"),
            javaRunModuleDeps.map("\n" + inspectItemIndent + _.toString + " (runtime)"),
            // Default Task:
            defaultTaskOpt.fold(Iterator.empty[String])(task =>
              Iterator("\n\n", ctx.applyPrefixColor("Default Task").toString, ": ", task)
            ),
            // Tasks (re-/defined):
            if (tasks.isEmpty) Iterator.empty[String]
            else Iterator(
              "\n\n",
              ctx.applyPrefixColor("Tasks (re-/defined)").toString,
              ":\n",
              inspectItemIndent,
              tasks.mkString("\n" + inspectItemIndent)
            )
          ).flatten
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
                // (If this logic is later found to be too harsh, we could further guard it,
                // to when none of the other paths exists.)
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
          existing.foreach(os.remove.all(_, ignoreErrors = true))
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
      MainModule.plan0(evaluator, targets) match {
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
        if (os.exists(os.pwd / "pom.xml"))
          RunScript.evaluateTasksNamed(
            evaluator,
            Seq("mill.init.InitMavenModule/init") ++ args,
            SelectMode.Separated
          )
        else if (
          os.exists(os.pwd / "build.gradle") ||
          os.exists(os.pwd / "build.gradle.kts") ||
          os.exists(os.pwd / "settings.gradle") ||
          os.exists(os.pwd / "settings.gradle.kts")
        )
          RunScript.evaluateTasksNamed(
            evaluator,
            Seq("mill.init.InitGradleModule/init") ++ args,
            SelectMode.Separated
          )
        else if (os.exists(os.pwd / "build.sbt"))
          RunScript.evaluateTasksNamed(
            evaluator,
            Seq("mill.init.InitSbtModule/init") ++ args,
            SelectMode.Separated
          )
        else if (args.headOption.exists(_.toLowerCase.endsWith(".g8")))
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
      ctx: mill.api.Ctx,
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

  /**
   * Commands related to selective execution, where Mill runs tasks selectively
   * depending on what task inputs or implementations changed
   */
  lazy val selective: SelectiveExecutionModule = new SelectiveExecutionModule {}

}
