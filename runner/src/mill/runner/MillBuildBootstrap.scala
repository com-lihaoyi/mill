package mill.runner
import mill.util.{ColorLogger, PrefixLogger, Util}
import mill.{BuildInfo, MillCliConfig, T}
import mill.api.{PathRef, internal}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.main.TokenReaders._
import mill.define.{BaseModule, Discover, Segments, SelectMode}
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

//  pprint.log(prevState.frames.map(f => if (f.classLoader == null) null else f.buildModule))
  val millBootClasspath = MillBuildBootstrap.prepareMillBootClasspath(projectRoot / "out")

  def evaluate(): Watching.Result[RunnerState] = {
    val runnerState = evaluateRec(0)

    for((frame, depth) <- runnerState.frames.zipWithIndex){
      os.write.over(
        recOut(depth) / "mill-runner-state.json",
        upickle.default.write(frame.loggedData, indent = 4),
        createFolders = true
      )
    }

    Watching.Result(
      watched = runnerState.frames.flatMap(_.watched),
      error = runnerState.errorOpt,
      result = runnerState
    )
  }

  def evaluateRec(depth: Int): RunnerState = {
    println(s"+evaluateRec($depth) " + recRoot(depth))
    val (baseModule0Opt, nestedRunnerState): (Option[BaseModule], RunnerState)  =
      if (os.exists(recRoot(depth) / "build.sc")) {
        val nestedRunnerState = evaluateRec(depth + 1)
        if (nestedRunnerState.errorOpt.isDefined) (None, nestedRunnerState)
        else {
          val nestedEvalState = nestedRunnerState.frames.head
          val cls = nestedEvalState.outputClassLoader.get.loadClass("millbuild.build$")
          val baseModule0 = cls.getField("MODULE$").get(cls).asInstanceOf[BaseModule]
          (Some(baseModule0), nestedRunnerState)
        }
      } else {
        val bootstrapModule =
          new MillBuildModule.BootstrapModule(projectRoot, recRoot(depth), millBootClasspath)(
            mill.runner.BaseModule.Info(recRoot(depth), Discover[MillBuildModule.BootstrapModule])
          )
          (Some(bootstrapModule), RunnerState.empty)
      }

    val res = baseModule0Opt match{
      case None =>
        println("errorState")
        RunnerState(
          Seq(RunnerState.Frame.empty) ++ nestedRunnerState.frames,
          nestedRunnerState.errorOpt
        )
      case Some(buildModule0) =>

        val childBaseModules = buildModule0
          .millModuleDirectChildren
          .collect { case b: BaseModule => b }

        val buildModule = childBaseModules match {
          case Seq() => buildModule0
          case Seq(child) => child
          case multiple =>
            throw new Exception(
              s"Only one BaseModule can be defined in a build, not " +
                s"${multiple.size}: ${multiple.map(_.getClass.getName).mkString(",")}"
            )
        }

        val prevFrameOpt = prevState.frames.lift(depth)

        val evaluator = makeEvaluator(
          prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
          nestedRunnerState.frames.lastOption.map(_.scriptImportGraph).getOrElse(Map.empty),
          // We want to use the grandparent buildHash, rather than the parent
          // buildHash, because the parent build changes are instead detected
          // by analyzing the scriptImportGraph in a more fine-grained manner.

          buildModule,
          nestedRunnerState.frames.lastOption.map(_.buildHash).getOrElse(0),
          depth
        )

        if (depth != 0) {
          println("processRunClasspath")
          processRunClasspath(nestedRunnerState.frames, evaluator, prevFrameOpt)
        } else {
          println("processFinalTargets")
          processFinalTargets(nestedRunnerState.frames, evaluator, buildModule)
        }
    }
    println(s"-evaluateRec($depth) " + recRoot(depth))
    res
  }

  def processRunClasspath(nestedEvalStates: Seq[RunnerState.Frame],
                          evaluator: Evaluator,
                          prevFrameOpt: Option[RunnerState.Frame]): RunnerState = {

    MillBuildBootstrap.evaluateWithWatches(
      evaluator,
      Seq("{runClasspath,scriptImportGraph}")
    ) {
      case Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[Path, Seq[Path]]) =>
        pprint.log(scriptImportGraph)
        prevFrameOpt.flatMap(_.outputClassLoader).foreach(_.close())
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
          Some(classloader)
        )

        RunnerState(Seq(evalState) ++ nestedEvalStates, None)
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
            None
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
