package mill.util

import mainargs.{Flag, Leftover}
import mill.{util, *}
import mill.api.daemon.internal.MainModuleApi
import mill.api.*
import mill.api.internal.{RootModule, RootModule0}
import mill.api.SelectMode.Separated
import mill.api.daemon.Watchable
import mill.moduledefs.Scaladoc
import mill.api.BuildCtx
import mill.api.daemon.internal.bsp.BspMainModuleApi
import scala.util.chaining.given

abstract class MainRootModule()(using
    baseModuleInfo: RootModule.Info,
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File
) extends RootModule with MainModule

/**
 * [[mill.api.Module]] containing all the default tasks that Mill provides: [[resolve]],
 * [[show]], [[inspect]], [[plan]], etc.
 */
trait MainModule extends RootModule0, MainModuleApi, JdkCommandsModule {

  private lazy val bspExt = {
    import bsp.BspMainModule.given
    ModuleRef(this.internalBspMainModule)
  }

  private[mill] def bspMainModule: () => BspMainModuleApi = () => bspExt()

  /**
   * Show the mill version.
   */
  @nonBootstrapped
  def version(): Command[String] = Task.Command(exclusive = true) {
    val res = BuildInfo.millVersion
    println(res)
    res
  }

  /**
   * Update the Mill bootstrap scripts (`./mill` and `./mill.bat`) in the project
   * to the specified version. Downloads the scripts from Maven Central and sets
   * executable permissions on the Unix script.
   *
   * You can also manually select the scripts to update, in which case only the specified script will be installed.
   *
   * @param version The Mill version to update to (e.g., "1.1.0")
   * @param shellScriptPath Override the location of the shell script, e.g "~/bin/mill".
   * @param batScriptPath Override the location of the Windows batch script.
   */
  @nonBootstrapped
  def updateMillScripts(
      @mainargs.arg(positional = true) version: String,
      shellScriptPath: String = null,
      batScriptPath: String = null
  ): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      val mavenRepoUrl = "https://repo1.maven.org/maven2"
      val baseUrl = s"$mavenRepoUrl/com/lihaoyi/mill-dist/$version"

      val useDefaults = shellScriptPath == null && batScriptPath == null

      val scripts = Seq(
        Option(shellScriptPath)
          .orElse(if (useDefaults) Some("mill") else None)
          .map(path =>
            (
              s"$baseUrl/mill-dist-$version-mill.sh",
              os.Path.expandUser(path, BuildCtx.workspaceRoot),
              true
            )
          ).toSeq,
        Option(batScriptPath)
          .orElse(if (useDefaults) Some("mill.bat") else None)
          .map(path =>
            (
              s"$baseUrl/mill-dist-$version-mill.bat",
              os.Path.expandUser(path, BuildCtx.workspaceRoot),
              false
            )
          ).toSeq
      ).flatten

      Task.log.info(s"Downloading Mill $version bootstrap scripts...")

      val results = scripts.flatMap { case (url, path, setExecutable) =>
        Task.log.info(s"Downloading $url")
        val response = requests.get(url, check = false)
        if (response.statusCode == 200) {
          os.write.over(path, response.bytes)
          if (setExecutable && !scala.util.Properties.isWin) os.perms.set(path, "rwxr-xr-x")
          Task.log.info(s"Updated $path")
          Some(PathRef(path))
        } else {
          Task.log.error(s"Failed to download $url: HTTP ${response.statusCode}")
          None
        }
      }

      if (results.isEmpty) {
        Result.Failure(
          s"Failed to download Mill $version bootstrap scripts. Please check that version '$version' exists."
        )
      } else {
        Task.log.info(s"Successfully updated Mill bootstrap scripts to version $version")
        Result.Success(results)
      }
    }

  /**
   * Resolves a mill query string and prints out the tasks it resolves to.
   */
  def resolve(evaluator: Evaluator, tasks: String*): Command[List[String]] =
    Task.Command(exclusive = true) {
      val resolved = evaluator.resolveSegments(tasks, SelectMode.Multi)

      resolved.map { resolvedSegmentsList =>
        val resolvedStrings = resolvedSegmentsList.map(_.render)
        resolvedStrings.foreach(println)
        resolvedStrings
      }
    }

  /**
   * Given a set of tasks, prints out the execution plan of what tasks will be
   * executed in what order, without actually executing them.
   */
  def plan(evaluator: Evaluator, tasks: String*): Command[Array[String]] =
    Task.Command(exclusive = true) {
      MainModule.plan0(evaluator, tasks).map {
        success =>
          val renderedTasks = success.map(_.toString)
          renderedTasks.foreach(println)
          renderedTasks
      }
    }

  /**
   * Prints out some dependency path from the `src` task to the `dest` task.
   *
   * Both `src` and `dest` can resolve to multiple tasks (e.g. via glob patterns),
   * and a path will be found from any task matching `src` to any task matching `dest`.
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
      for {
        srcTasks <- evaluator.resolveTasks(List(src), SelectMode.Multi)
        destTasks <- evaluator.resolveTasks(List(dest), SelectMode.Multi)
        result <- MainModule.pathBetween(
          src,
          dest,
          srcTasks,
          destTasks,
          _.inputs
        )
      } yield result
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
  def show(evaluator: Evaluator, tasks: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      MainModule.show0(evaluator, tasks, Task.log, BuildCtx.evalWatch0) { res =>
        res.flatMap(_._2) match {
          case Seq((_, singleValue)) => singleValue
          case multiple => ujson.Obj.from(multiple)
        }
      }
    }

  /**
   * Runs a given task and prints the results as JSON dictionary to stdout. This is useful
   * to integrate Mill into external scripts and tooling.
   */
  def showNamed(evaluator: Evaluator, tasks: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      MainModule.show0(evaluator, tasks, Task.log, BuildCtx.evalWatch0) { res =>
        ujson.Obj.from(res.flatMap(_._2))
      }
    }

  /**
   * Deletes the given targets from the out directory. Providing no targets
   * will clean everything.
   */
  def clean(evaluator: Evaluator, tasks: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) { cleanTask(evaluator, tasks*)() }

  def cleanTask(evaluator: Evaluator, tasks: String*) = Task.Anon {
    val rootDir = evaluator.outPath

    val KeepPattern = "(mill-.+)".r.anchored

    def keepPath(path: os.Path) = path.last match {
      case KeepPattern(_) => true
      case _ => false
    }

    val pathsToRemove =
      if (tasks.isEmpty)
        Result.Success((os.list(rootDir).filterNot(keepPath), List(mill.api.Segments())))
      else
        evaluator.resolveSegments(tasks, SelectMode.Multi).map { ts =>
          val allPaths = ts.flatMap { segments =>
            val evPaths = ExecutionPaths.resolve(rootDir, segments)
            val paths = Seq(evPaths.dest, evPaths.meta, evPaths.log)
            val potentialModulePath =
              rootDir / segments.parts.map(ExecutionPaths.sanitizePathSegment)
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

    (pathsToRemove.runtimeChecked).map {
      case (paths, allSegments) =>
        for {
          workerSegments <- evaluator.workerCache.keys.toList
          if allSegments.exists(x => workerSegments.startsWith(x.render))
          case (_, Val(closeable: AutoCloseable), _) <-
            evaluator.workerCache.remove(workerSegments)
        } {
          closeable.close()
        }

        val existing = paths.filter(p => os.exists(p))
        Task.log.debug(s"Cleaning ${existing.size} paths ...")
        existing.foreach(os.remove.all(_, ignoreErrors = true))
        existing.map(PathRef(_))
    }

  }

  /**
   * Renders the dependencies between the given tasks as a SVG for you to look at
   */
  def visualize(evaluator: Evaluator, tasks: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      VisualizeModule.visualize0(
        evaluator,
        tasks,
        Task.ctx(),
        util.VisualizeModule.worker()
      )
    }

  /**
   * Renders the dependencies between the given tasks, and all their dependencies, as a SVG
   */
  def visualizePlan(evaluator: Evaluator, tasks: String*): Command[Seq[PathRef]] =
    Task.Command(exclusive = true) {
      MainModule.plan0(evaluator, tasks).flatMap {
        planResults =>
          VisualizeModule.visualize0(
            evaluator,
            tasks,
            Task.ctx(),
            util.VisualizeModule.worker(),
            Some(planResults.toList)
          )
      }
    }

  /**
   * Shuts down mill's background daemon
   */
  @nonBootstrapped
  def shutdown(): Command[Unit] = Task.Command(exclusive = true) {
    Task.log.info("Shutting down Mill server...")
    Task.ctx().systemExitWithReason("`shutdown` command received", 0)
    ()
  }

  private case class InitArgs(
      @mainargs.arg(doc = "Print this help and exit")
      help: Flag = Flag(false),
      @mainargs.arg(doc = "Initialize a Mill project from a Giter8 template.")
      giter8: Flag = Flag(false),
      @mainargs.arg(doc = "Create a Mill project by migrating from a Maven project.")
      maven: Flag = Flag(false),
      @mainargs.arg(doc = "Create a Mill project by migrating from a Gradle project.")
      gradle: Flag = Flag(false),
      @mainargs.arg(doc = "Create a Mill project by migrating from a sbt project.")
      sbt: Flag = Flag(false),
      @mainargs.arg(doc = "Initialize a Mill project from an example project.")
      example: Flag = Flag(false),
      rest: Leftover[String]
  )

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
  @nonBootstrapped
  def init(evaluator: Evaluator, args: String*): Command[Unit] =
    Task.Command(exclusive = true) {

      val parser = mainargs.Parser[InitArgs]
      val parsed = parser.constructOrThrow(args)
      if (parsed.help.value) {
        // systemExit isn't ideal, since it stops the Mill daemon, but we don't want to fail the build
        Task.ctx().systemExitWithReason(parser.helpText(), 0)
      }

      enum Mode(
          val entryPoint: String,
          val selected: InitArgs => Boolean,
          val guess: InitArgs => Boolean
      ) {
        case Maven extends Mode(
              entryPoint = "mill.init.InitMavenModule/init",
              selected = _.maven.value,
              guess = _ => os.exists(os.pwd / "pom.xml")
            )
        case Gradle extends Mode(
              entryPoint = "mill.init.InitGradleModule/init",
              selected = _.gradle.value,
              guess = _ =>
                (
                  os.exists(os.pwd / "build.gradle") ||
                    os.exists(os.pwd / "build.gradle.kts") ||
                    os.exists(os.pwd / "settings.gradle") ||
                    os.exists(os.pwd / "settings.gradle.kts")
                )
            )
        case Sbt extends Mode(
              entryPoint = "mill.init.InitSbtModule/init",
              selected = _.sbt.value,
              guess = _ => os.exists(os.pwd / "build.sbt")
            )
        case Giter8 extends Mode(
              entryPoint = "mill.init.Giter8Module/init",
              selected = _.giter8.value,
              guess = _.rest.value.headOption.exists(_.toLowerCase.endsWith(".g8"))
            )
        case Example extends Mode(
              entryPoint = "mill.init.InitModule/init",
              selected = _.example.value,
              guess = _ => false
            )
      }

      val selected = Mode.values
        // 1. Prefer explicit selection
        .filter(_.selected(parsed))
        // check and guess
        .pipe { selected =>
          selected.length match {
            case 1 => selected.headOption
            // nothing selected, need to guess the mode
            case 0 => Mode.values.find(_.guess(parsed))
            case _ => Task.fail(
                "You can only select one of: --giter8, --maven, --gradle, --sbt or --dontGuess"
              )
          }
        }
        // fallback to Example
        .getOrElse(Mode.Example)

      val evaluated =
        evaluator.evaluate(Seq(selected.entryPoint) ++ parsed.rest.value, SelectMode.Separated)

      (evaluated.runtimeChecked) match {
        case f: Result.Failure => f
        case Result.Success(Evaluator.Result(
              _,
              Result.Success(Seq(_)),
              _,
              _
            )) =>
          Result.Success(())
        case Result.Success(Evaluator.Result(_, f: Result.Failure, _, _)) =>
          f
      }
    }

  /**
   * Commands related to selective execution, where Mill runs tasks selectively
   * depending on what task inputs or implementations changed.
   *
   * Read more about it at: https://mill-build.org/mill/large/selective-execution.html
   *
   * Here are the essential commands:
   *
   * - `mill selective.prepare <selector>`:
   *   run on the codebase before the code change,
   *   stores a snapshot of task inputs and implementations
   *
   * - `mill selective.run <selector>`:
   *   run on the codebase after the code change,
   *   runs tasks in the given `<selector>` which are affected by the code changes
   *   that have happened since `selective.prepare` was run
   *
   * - `mill selective.resolve <selector>`:
   *   a dry-run version of `selective.run`,
   *   prints out the tasks in `<selector>` that are affected by the code changes
   *   and would have run, without actually running them.
   */
  lazy val selective: SelectiveExecutionModule = new SelectiveExecutionModule {}

}

object MainModule {
  private[mill] def pathBetween(
      srcLabel: String,
      destLabel: String,
      srcTasks: Seq[Task[?]],
      destTasks: Seq[Task[?]],
      edges: Task[?] => IterableOnce[Task[?]]
  ): Result[List[String]] = {
    val destSet: Set[Task[?]] = destTasks.toSet
    val queue = collection.mutable.Queue[List[Task[?]]](srcTasks.map(List(_))*)
    var found = Option.empty[List[Task[?]]]
    val seen = collection.mutable.Set.empty[Task[?]]
    seen ++= srcTasks
    while (queue.nonEmpty && found.isEmpty) {
      val current = queue.dequeue()
      if (destSet.contains(current.head)) found = Some(current)
      else {
        for {
          next <- edges(current.head).iterator
          if !seen.contains(next)
        } {
          seen.add(next)
          queue.enqueue(next :: current)
        }
      }
    }
    found match {
      case None => Task.fail(s"No path found between $srcLabel and $destLabel")
      case Some(list) =>
        val labels = list.collect { case n: Task.Named[_] => n.ctx.segments.render }.reverse
        labels.foreach(println)
        Result.Success(labels.toList)
    }
  }

  private def show0(
      evaluator: Evaluator,
      tasks: Seq[String],
      log: Logger,
      watch0: Watchable => Unit
  )(f: Seq[(Any, Option[(String, ujson.Value)])] => ujson.Value)
      : Result[ujson.Value] = {

    // When using `show`, redirect all stdout of the evaluated tasks so the
    // printed JSON is the only thing printed to stdout.
    val redirectLogger = log
      .withRedirectOutToErr()
      .asInstanceOf[Logger]

    evaluator.withBaseLogger(redirectLogger)
      .evaluate(
        tasks,
        Separated,
        selectiveExecution = evaluator.selectiveExecution
      ).flatMap {
        case Evaluator.Result(watched, f: Result.Failure, _, _) =>
          watched.foreach(watch0)
          f

        case Evaluator.Result(watched, Result.Success(_), selectedTasks, executionResults) =>
          val namesAndJson = for (t <- selectedTasks) yield {
            t match {
              case t: mill.api.Task.Named[_] =>
                // Use the in-memory result from executionResults instead of reading from
                // paths.meta file. This ensures show displays the correct value even when
                // the in-memory result differs from what's cached on disk (e.g., with !append)
                val jsonOpt = for {
                  execResult <- executionResults.transitiveResults.get(t)
                  success <- execResult.asSuccess
                } yield {
                  t.writerOpt match {
                    case Some(writer) =>
                      upickle.writeJs(success.value.value)(using
                        writer.asInstanceOf[upickle.Writer[Any]]
                      )
                    case None if t.asWorker.isDefined =>
                      // Workers don't have a writer, use shared worker JSON generation
                      Task.workerJson(
                        t.toString,
                        success.value.value,
                        success.value.value.hashCode()
                      )
                    case None =>
                      ujson.Null
                  }
                }
                jsonOpt.map(json => (t.toString, json))
              case _ => None
            }
          }
          val output = f(selectedTasks.zip(namesAndJson))
          watched.foreach(watch0)
          println(output.render(indent = 2))
          Result.Success(output)
      }
  }

  def plan0(
      evaluator: Evaluator,
      tasks: Seq[String]
  ): Result[Array[Task.Named[?]]] = {
    evaluator.resolveTasks(tasks, SelectMode.Multi).map {
      rs =>
        val plan = evaluator.plan(rs)
        plan.sortedGroups.keys().collect { case r: Task.Named[_] => r }.toArray
    }
  }

}
