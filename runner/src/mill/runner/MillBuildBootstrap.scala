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
 * each evaluation is cached in-memory in [[prevRunnerState]].
 *
 * When a subsequent evaluation happens, each level of [[evaluateRec]] uses
 * its corresponding frame from [[prevRunnerState]] to avoid work, re-using
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
                         prevRunnerState: RunnerState,
                         logger: ColorLogger){

  val millBootClasspath = MillBuildBootstrap.prepareMillBootClasspath(projectRoot / "out")

  def evaluate(): Watching.Result[RunnerState] = {
    val runnerState = evaluateRec(0)

    pprint.log(prevRunnerState.frames.map(_.inputClassloader))
    for((frame, depth) <- runnerState.frames.zipWithIndex){
      os.write.over(
        recOut(depth) / "mill-runner-state.json",
        upickle.default.write(frame.loggedData, indent = 4),
        createFolders = true
      )
    }

    Watching.Result(
      watched = runnerState.frames.flatMap(_.outputWatched),
      error = runnerState.errorOpt,
      result = runnerState
    )
  }

  def getModule0(runClassLoader: URLClassLoader) = {
    val cls = runClassLoader.loadClass("millbuild.build$")
    cls.getField("MODULE$").get(cls).asInstanceOf[BaseModule]
  }

  def evaluateRec(depth: Int): RunnerState = {
    println(s"+evaluateRec($depth) " + recRoot(depth))
    val prevFrameOpt = prevRunnerState.frames.lift(depth)

    val (classLoaderOpt, baseModule0Opt, nestedRunnerState) =
      if (os.exists(recRoot(depth) / "build.sc")) {
        val nestedRunnerState = evaluateRec(depth + 1)
        if (nestedRunnerState.errorOpt.isDefined) (None, None, nestedRunnerState)
        else {
          val nestedFrame = nestedRunnerState.frames.head
          val prevNestedFrameOpt = prevRunnerState.frames.lift(depth + 1)
          val prevClassLoaderOpt = prevFrameOpt
            .flatMap(_.inputClassloader)
            .filter(_ => prevNestedFrameOpt.exists(_.outputRunClasspath == nestedFrame.outputRunClasspath))

          val classLoader = prevClassLoaderOpt.getOrElse(
            new URLClassLoader(
              nestedFrame.outputRunClasspath.map(_.path.toNIO.toUri.toURL).toArray,
              getClass.getClassLoader
            )
          )

          (Some(classLoader), Some(getModule0(classLoader)), nestedRunnerState)
        }
      } else {
        val bootstrapModule =
          new MillBuildModule.BootstrapModule(projectRoot, recRoot(depth), millBootClasspath)(
            mill.runner.BaseModule.Info(recRoot(depth), Discover[MillBuildModule.BootstrapModule])
          )
          (None, Some(bootstrapModule), RunnerState.empty)
      }

    val res = baseModule0Opt match{
      case None =>
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

        val evaluator = makeEvaluator(
          prevFrameOpt.map(_.outputWorkerCache).getOrElse(Map.empty),
          nestedRunnerState.frames.lastOption.map(_.outputScriptImportGraph).getOrElse(Map.empty),
          // We want to use the grandparent buildHash, rather than the parent
          // buildHash, because the parent build changes are instead detected
          // by analyzing the scriptImportGraph in a more fine-grained manner.

          buildModule,
          nestedRunnerState.frames.lastOption.map(_.buildHash).getOrElse(0),
          depth
        )

        if (depth != 0) {
          println("processRunClasspath " + classLoaderOpt)
          processRunClasspath(nestedRunnerState.frames, evaluator, classLoaderOpt, prevFrameOpt)
        } else {
          println("processFinalTargets")
          processFinalTargets(nestedRunnerState.frames, evaluator, classLoaderOpt, buildModule)
        }
    }
    println(s"-evaluateRec($depth) " + recRoot(depth))
    res
  }

  def processRunClasspath(nestedFrames: Seq[RunnerState.Frame],
                          evaluator: Evaluator,
                          classLoaderOpt: Option[URLClassLoader],
                          prevFrameOpt: Option[RunnerState.Frame]): RunnerState = {

    MillBuildBootstrap.evaluateWithWatches(evaluator, Seq("{runClasspath,scriptImportGraph}")) match {
      case (Left(error), watches) =>
        val evalState = RunnerState.Frame(Map.empty, watches, Map.empty, None, Nil)
        RunnerState(Seq(evalState) ++  nestedFrames, Some(error))

      case (Right(Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[Path, Seq[Path]])), watches) =>
        pprint.log(scriptImportGraph)
        prevFrameOpt.flatMap(_.inputClassloader).foreach(_.close())

        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          watches,
          scriptImportGraph,
          classLoaderOpt,
          runClasspath
        )

        RunnerState(Seq(evalState) ++ nestedFrames, None)
    }
  }

  def processFinalTargets(nestedEvalStates: Seq[RunnerState.Frame],
                          evaluator: Evaluator,
                          classLoaderOpt: Option[URLClassLoader],
                          buildModule: BaseModule): RunnerState = {

    Util.withContextClassloader(buildModule.getClass.getClassLoader) {
      val (evaled, buildWatched) =
        MillBuildBootstrap.evaluateWithWatches(evaluator, targetsAndParams)

      evaled match {
        case Left(error) =>
          RunnerState(Seq(RunnerState.Frame.empty) ++ nestedEvalStates, Some(error))

        case Right(_) =>
          val evalState = RunnerState.Frame(
            evaluator.workerCache.toMap,
            buildWatched,
            Map.empty,
            classLoaderOpt,
            Nil
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

  def evaluateWithWatches(evaluator: Evaluator,
                          targetsAndParams: Seq[String]): (Either[String, Seq[Any]], Seq[Watchable]) = {
    println("")
    RunScript.evaluateTasks(evaluator, targetsAndParams, SelectMode.Separated) match {
      case Left(msg) => (Left(msg), Nil)
      case Right((watchedPaths, evaluated)) =>
        val watched = watchedPaths.map(Watchable.Path)
        evaluated match {
          case Left(msg) => (Left(msg), watched)
          case Right(results) => (Right(results.map(_._1)), watched)
        }
    }
  }
}
