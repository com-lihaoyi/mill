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

  def getModule0(runClassLoader: URLClassLoader) = {
    val cls = runClassLoader.loadClass("millbuild.build$")
    cls.getField("MODULE$").get(cls).asInstanceOf[BaseModule]
  }

  def evaluateRec(depth: Int): RunnerState = {
     println(s"+evaluateRec($depth) " + recRoot(depth))
    val prevFrameOpt = prevRunnerState.frames.lift(depth)

    val nestedRunnerState =
      if (!os.exists(recRoot(depth) / "build.sc")) {
        val bootstrapModule =
          new MillBuildModule.BootstrapModule(projectRoot, recRoot(depth), millBootClasspath)(
            mill.runner.BaseModule.Info(recRoot(depth), Discover[MillBuildModule.BootstrapModule])
          )
        RunnerState(Some(bootstrapModule), Nil, None)
      }else {
        evaluateRec(depth + 1)
      }

    val res = if (nestedRunnerState.errorOpt.isDefined) {
      nestedRunnerState.add(errorOpt = nestedRunnerState.errorOpt)
    } else{
      val baseModule0 = nestedRunnerState.frames.headOption match{
        case None => nestedRunnerState.bootstrapModuleOpt.get
        case Some(nestedFrame) => getModule0(nestedFrame.classLoaderOpt.get)
      }

      val childBaseModules = baseModule0
        .millModuleDirectChildren
        .collect { case b: BaseModule => b }

      val buildModule = childBaseModules match {
        case Seq() => baseModule0
        case Seq(child) => child
        case multiple =>
          throw new Exception(
            s"Only one BaseModule can be defined in a build, not " +
              s"${multiple.size}: ${multiple.map(_.getClass.getName).mkString(",")}"
          )
      }

      val evaluator = makeEvaluator(
        prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
        nestedRunnerState.frames.lastOption.map(_.scriptImportGraph).getOrElse(Map.empty),
        buildModule,
        // We want to use the grandparent buildHash, rather than the parent
        // buildHash, because the parent build changes are instead detected
        // by analyzing the scriptImportGraph in a more fine-grained manner.
        nestedRunnerState
          .frames
          .dropRight(1)
          .headOption
          .map(_.runClasspath.hashCode())
          .getOrElse(0),
        depth
      )

      if (depth != 0) processRunClasspath(nestedRunnerState, evaluator, prevFrameOpt)
      else processFinalTargets(nestedRunnerState, evaluator)
    }
     println(s"-evaluateRec($depth) " + recRoot(depth))
    res
  }

  /**
   * Handles the compilation of `build.sc` or one of the meta-builds. These
   * cases all only need us to run evaluate `runClasspath` and
   * `scriptImportGraph` to instantiate their classloader/`BaseModule` to feed
   * into the next level's [[Evaluator]].
   *
   * Note that if the `runClasspath` doesn't change, we re-use the previous
   * classloader, saving us from having to re-instantiate it and for the code
   * inside to be re-JITed
   */
  def processRunClasspath(nestedRunnerState: RunnerState,
                          evaluator: Evaluator,
                          prevFrameOpt: Option[RunnerState.Frame]): RunnerState = {

    MillBuildBootstrap.evaluateWithWatches(evaluator, Seq("{runClasspath,scriptImportGraph}")) match {
      case (Left(error), watches) =>
        val evalState = RunnerState.Frame(Map.empty, watches, Map.empty, None, Nil)
        nestedRunnerState.add(frame = evalState, errorOpt = Some(error))

      case (Right(Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[Path, (Int, Seq[Path])])), watches) =>

        val runClasspathChanged = !prevFrameOpt.exists(
          _.runClasspath.map(_.sig).sum == runClasspath.map(_.sig).sum
        )

        pprint.log(runClasspathChanged)
        val debugPath = projectRoot / "out" / "mill-build" / "compile.dest" / "classes"
        if (runClasspathChanged && os.exists(debugPath)){
          pprint.log(os.walk(debugPath).map(PathRef(_).toString))
          val temp = os.temp.dir()
          os.copy.over(debugPath, temp)
          pprint.log(temp)
        }

        val classLoader = if (runClasspathChanged){
          // Make sure we close the old classloader every time we create a new
          // one, to avoid memory leaks

          prevFrameOpt.foreach(_.classLoaderOpt.foreach(_.close()))
          val cl = new RunnerState.URLClassLoader(
            runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
            getClass.getClassLoader
          )
          cl
        }else{
          prevFrameOpt.get.classLoaderOpt.get
        }
        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          watches,
          scriptImportGraph,
          Some(classLoader),
          runClasspath
        )

        nestedRunnerState.add(frame = evalState)
    }
  }

  /**
   * Handles the final evaluation of the user-provided targets. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTargets(nestedRunnerState: RunnerState,
                          evaluator: Evaluator): RunnerState = {

//    Util.withContextClassloader(nestedRunnerState.frames.head.classLoaderOpt.get) {
      val (evaled, buildWatched) =
        MillBuildBootstrap.evaluateWithWatches(evaluator, targetsAndParams)

      evaled match {
        case Left(error) => nestedRunnerState.add(errorOpt = Some(error))

        case Right(_) =>
          val evalState = RunnerState.Frame(
            evaluator.workerCache.toMap,
            buildWatched,
            Map.empty,
            None,
            Nil
          )

          nestedRunnerState.add(frame = evalState)
      }
//    }
  }

  def makeEvaluator(workerCache: Map[Segments, (Int, Any)],
                    scriptImportGraph: Map[Path, (Int, Seq[Path])],
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
