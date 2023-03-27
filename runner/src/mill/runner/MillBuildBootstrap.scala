package mill.runner
import mill.util.{ColorLogger, PrefixLogger, Util}
import mill.{BuildInfo, MillCliConfig}
import mill.api.{PathRef, internal}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.define.{BaseModule, Segments, SelectMode}
import os.Path

import java.net.URLClassLoader

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[BaseModule]]s so we
 * can evaluate the requested tasks on the [[BaseModule]] representing the user's
 * `build.sc` file.
 *
 * When Mill is run in client-server mode, or with `--watch`, then data from
 * each evaluation is cached in-memory in [[prevState]]. This contains a list
 * of frames each representing cached data from a single level of evaluation:
 *
 * - `frame(0)` contains the output of evaluating the user-given targets
 * - `frame(1)` contains the output of `build.sc` file compilation
 * - `frame(2)` contains the output of the in-memory [[MillBuildModule.BootstrapModule]]
 * - If there are meta-builds present (e.g. `mill-build/build.sc`), then `frame(2)`
 *   would contains the output of the meta-build compilation, and the in-memory
 *   bootstrap module would be pushed to a higher frame
 *
 * When a subsequent evaluation happens, each level of [[evaluateRec]] uses
 * its corresponding frame from [[prevState]] to avoid work, re-using
 * classloaders or workers to avoid running expensive classloading or
 * re-evaluation. This should be transparent, improving performance without
 * affecting behavior.
 */
@internal
class MillBuildBootstrap(projectRoot: os.Path,
                         config: MillCliConfig,
                         env: Map[String, String],
                         threadCount: Option[Int],
                         targetsAndParams: Seq[String],
                         prevState: RunnerState,
                         logger: ColorLogger){

  val millBootClasspath = MillBuildBootstrap.prepareMillBootClasspath(projectRoot / "out")

  def evaluate(): Watching.Result[RunnerState] = {
    val multiState = evaluateRec(0)
    Watching.Result(
      watched = multiState.frames.flatMap(_.watched),
      error = multiState.errorOpt,
      result = multiState
    )
  }

  def evaluateRec(depth: Int): RunnerState = {
    val recEither =
      if (os.exists(recRoot(depth) / "build.sc")) Right(evaluateRec(depth + 1))
      else Left{
        val bootRoot = recRoot(depth)
        new MillBuildModule.BootstrapModule(projectRoot, bootRoot, millBootClasspath)(
          mill.runner.BaseModule.Info(bootRoot)
        )
      }

    val (nestedDataOrError, nestedEvalStates) = recEither match {
      case Left(millBuildModule) =>
        (Right((millBuildModule, Map.empty[os.Path, Seq[os.Path]], 0)), Nil)

      case Right(metaRunnerState) =>
        if (metaRunnerState.errorOpt.isDefined) {
          (Left(metaRunnerState), metaRunnerState.frames)
        } else {
          val nestedEvalState = metaRunnerState.frames.head
          val nestedData = (
            nestedEvalState.buildModule,
            nestedEvalState.scriptImportGraph,
            // We want to use the grandparent buildHash, rather than the parent
            // buildHash, because the parent build changes are instead detected
            // by analyzing the scriptImportGraph in a more fine-grained manner.
            metaRunnerState
              .frames
              .dropRight(1)
              .headOption
              .map(_.buildHash)
              .getOrElse(0)
          )

          (Right(nestedData), metaRunnerState.frames)
        }
    }

    nestedDataOrError match{
      case Left(errorState) => errorState
      case Right((buildModule0, scriptImportGraph, buildSigHash)) =>

        val childBaseModules = buildModule0
          .millModuleDirectChildren
          .collect {case b: BaseModule => b}

        val buildModuleOrError = childBaseModules match{
          case Seq() => Right(buildModule0)
          case Seq(child) => Right(child)
          case multiple =>
            Left(
              s"Only one BaseModule can be defined in a build, not " +
              s"${multiple.size}: ${multiple.map(_.getClass.getName).mkString(",")}"
            )
        }

        buildModuleOrError match{
          case Left(error) =>
            RunnerState(Seq(RunnerState.Frame.empty) ++ nestedEvalStates, Some(error))

          case Right(buildModule) =>
            val prevFrameOpt = prevState.frames.lift(depth)

            val evaluator = makeEvaluator(
              prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
              scriptImportGraph,
              buildModule,
              buildSigHash,
              depth
            )

            if (depth != 0) {
              processRunClasspath(nestedEvalStates, evaluator, prevFrameOpt)
            } else {
              processFinalTargets(nestedEvalStates, evaluator, buildModule)
            }
        }
    }
  }

  def processRunClasspath(nestedEvalStates: Seq[RunnerState.Frame],
                          evaluator: Evaluator,
                          prevFrameOpt: Option[RunnerState.Frame]): RunnerState = {
    prevFrameOpt match {
      // Check whether the buildModule has been re-evaluated since we last
      // saw it. If the build module hasn't re-evaluated, we can skip
      // evaluating anything and just use the cached data since it will all
      // evaluate to the same values anyway
      case Some(s) if prevFrameOpt.exists(_.buildModule eq evaluator.rootModule) =>
        RunnerState(Seq(s) ++ nestedEvalStates, None)
      case _ =>
        MillBuildBootstrap.evaluateWithWatches(
          evaluator,
          Seq("{runClasspath,scriptImportGraph}")
        ) {
          case Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[Path, Seq[Path]]) =>
            prevFrameOpt.foreach(_.classLoader.close())
            val runClassLoader = new URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
              getClass.getClassLoader
            )

            (runClassLoader, scriptImportGraph)
        } match {
          case (Left(error), watches) =>
            val evalState = RunnerState.Frame(
              Map.empty,
              watches,
              Map.empty,
              null
            )
            RunnerState(Seq(evalState) ++  nestedEvalStates, Some(error))

          case (Right((classloader, scriptImportGraph)), watches) =>
            val evalState = RunnerState.Frame(
              evaluator.workerCache.toMap,
              watches,
              scriptImportGraph,
              classloader
            )

            RunnerState(Seq(evalState) ++ nestedEvalStates, None)
        }
    }
  }

  def processFinalTargets(nestedEvalStates: Seq[RunnerState.Frame],
                          evaluator: Evaluator,
                          buildModule: BaseModule): RunnerState = {

    Util.withContextClassloader(buildModule.getClass.getClassLoader) {
      val (evaled, buildWatched) =
        MillBuildBootstrap.evaluateWithWatches(evaluator, targetsAndParams) { _ => () }

      evaled match {
        case Left(error) =>
          RunnerState(Seq(RunnerState.Frame.empty) ++ nestedEvalStates, Some(error))

        case Right(_) =>
          val evalState = RunnerState.Frame(
            evaluator.workerCache.toMap,
            buildWatched,
            Map.empty,
            null
          )

          RunnerState(Seq(evalState) ++ nestedEvalStates, None)
      }
    }
  }

  def makeEvaluator(workerCache: Map[Segments, (Int, Any)],
                    scriptImportGraph: Map[Path, Seq[Path]],
                    baseModule: BaseModule,
                    millClassloaderSigHash: Int,
                    depth: Int) = {
    val bootLogPrefix =
      if (depth == 0) ""
      else "[" + (Seq.fill(depth-1)("mill-build") ++ Seq("build.sc")).mkString("/") + "] "

    Evaluator(
      config.home,
      recOut(depth),
      recOut(depth),
      baseModule,
      PrefixLogger(logger, "", tickerContext = bootLogPrefix),
      millClassloaderSigHash
    )
      .withWorkerCache(workerCache.to(collection.mutable.Map))
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withScriptImportGraph(scriptImportGraph)
  }


  def recRoot(depth: Int) = projectRoot / Seq.fill(depth)("mill-build")
  def recOut(depth: Int) = projectRoot / "out" / Seq.fill(depth)("mill-build")

}

@internal
object MillBuildBootstrap{
  def prepareMillBootClasspath(millBuildBase: Path) = {
    val enclosingClasspath: Seq[Path] = mill.util.Classpath
      .classpath(getClass.getClassLoader)

    val selfClassURL = getClass.getProtectionDomain().getCodeSource().getLocation()
    assert(selfClassURL.getProtocol == "file")
    val selfClassLocation = os.Path(java.nio.file.Paths.get(selfClassURL.toURI))

    // Copy the current location of the enclosing classes to `mill-launcher.jar`
    // if it has the wrong file extension, because the Zinc incremental compiler
    // doesn't recognize classpath entries without the proper file extension
    val millLauncherOpt =
    if (os.isFile(selfClassLocation) &&
      !Set("zip", "jar", "class").contains(selfClassLocation.ext)) {

      val millLauncher =
        millBuildBase / "mill-launcher" / s"${BuildInfo.millVersion}.jar"

      if (!os.exists(millLauncher)) {
        os.copy(selfClassLocation, millLauncher, createFolders = true, replaceExisting = true)
      }
      Some(millLauncher)
    } else None
    enclosingClasspath ++ millLauncherOpt
  }

  def evaluateWithWatches[T](evaluator: Evaluator,
                             targetsAndParams: Seq[String])
                            (handleResults: Seq[Any] => T): (Either[String, T], Seq[Watchable]) = {
    RunScript.evaluateTasks(evaluator, targetsAndParams, SelectMode.Separated) match {
      case Left(msg) => (Left(msg), Nil)
      case Right((watchedPaths, evaluated)) =>
        val watched = watchedPaths.map(Watchable.Path)
        evaluated match {
          case Left(msg) => (Left(msg), watched)
          case Right(results) => (Right(handleResults(results.map(_._1))), watched)
        }
    }
  }
}
