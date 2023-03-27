package mill.runner
import mill.util.{ColorLogger, PrefixLogger, Util}
import mill.{BuildInfo, MillCliConfig}
import mill.api.{PathRef, internal}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.define.{BaseModule, SelectMode}
import os.Path

import java.net.URLClassLoader

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[BaseModule]]s so we
 * can evaluate the requested tasks on the [[BaseModule]] representing the user's
 * `build.sc` file.
 */
@internal
class MillBuildBootstrap(projectRoot: os.Path,
                         config: MillCliConfig,
                         env: Map[String, String],
                         threadCount: Option[Int],
                         targetsAndParams: Seq[String],
                         stateCache: RunnerState,
                         logger: ColorLogger){

  val millBootClasspath = MillBuildBootstrap.prepareMillBootClasspath(projectRoot / "out")

  def evaluate(): Watching.Result[RunnerState] = {
    val multiState = evaluateRec(0)
    Watching.Result(
      watched = multiState.evalStates.flatMap(_.watched),
      error = multiState.errorAndDepth.map(_._1),
      result = multiState
    )
  }

  def evaluateRec(depth: Int): RunnerState = {
    val prevStateOpt = stateCache
      .evalStates
      .lift(depth + stateCache.errorAndDepth.map(_._2).getOrElse(0))

    val buildFileExists = os.exists(recRoot(depth) / "build.sc")
    val recEither =
      if (buildFileExists) Right(evaluateRec(depth + 1))
      else Left{
        val bootRoot = recRoot(depth)
        new MillBuildModule.BootstrapModule(projectRoot, bootRoot, millBootClasspath)(
          mill.runner.BaseModule.Info(bootRoot)
        )
      }

    val buildModuleOrError = recEither match {
      case Left(millBuildModule) => Right((millBuildModule, Map.empty[os.Path, Seq[os.Path]]))
      case Right(metaRunnerState) =>
        if (metaRunnerState.errorAndDepth.isDefined) Left(metaRunnerState)
        else {
          val nestedEvalState = metaRunnerState.evalStates.head
          Right((nestedEvalState.buildModule, nestedEvalState.scriptImportGraph))
        }
    }

    val nestedEvalStates = recEither.toSeq.toList.flatMap(_.evalStates)

    val res = buildModuleOrError match{
      case Left(errorState) => errorState
      case Right((buildModule0, scriptImportGraph)) =>

        val buildModule = buildModule0
          .millModuleDirectChildren
          .collectFirst{case b: BaseModule => b}
          .getOrElse(buildModule0)

        val evaluator = makeEvaluator(
          prevStateOpt,
          scriptImportGraph,
          buildModule,
          0,
          depth
        )

        if (depth != 0) {
          processRunClasspath(depth, prevStateOpt, 0, evaluator, nestedEvalStates)
        } else {
          processFinalTargets(depth, nestedEvalStates, buildModule, evaluator)
        }
    }

    res
  }


  def processRunClasspath(depth: Int,
                          prevStateOpt: Option[RunnerState.Frame],
                          millClassloaderSigHash: Int,
                          evaluator: Evaluator,
                          previousEvalStates: List[RunnerState.Frame]): RunnerState = {
    val (bootClassloaderImportGraphOrErr, bootWatched) = prevStateOpt match {
      case Some(s)
        if s.watched.forall(_.validate())
          && prevStateOpt.exists(_.buildModule eq evaluator.rootModule) =>

        Right((s.classLoader, s.scriptImportGraph)) -> s.watched

      case _ =>
        MillBuildBootstrap.evaluateWithWatches(
          evaluator,
          Seq("{runClasspath,scriptImportGraph}")
        ) {
          case Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[Path, Seq[Path]]) =>
            prevStateOpt.foreach(_.classLoader.close())
            val runClassLoader = new URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
              getClass.getClassLoader
            )

            (runClassLoader, scriptImportGraph)
        }
    }

    val res = bootClassloaderImportGraphOrErr match {
      case Left(error) => RunnerState(previousEvalStates, Some(error, depth))
      case Right((classloader, scriptImportGraph)) =>
        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          bootWatched,
          scriptImportGraph,
          classloader
        )

        RunnerState(evalState :: previousEvalStates, None)
    }

    res
  }

  def processFinalTargets(depth: Int,
                          nestedEvalStates: List[RunnerState.Frame],
                          buildModule: BaseModule,
                          evaluator: Evaluator): RunnerState = {

    Util.withContextClassloader(buildModule.getClass.getClassLoader) {
      val (evaled, buildWatched) =
        MillBuildBootstrap.evaluateWithWatches(evaluator, targetsAndParams) { _ => () }

      val evalState = RunnerState.Frame(
        evaluator.workerCache.toMap,
        buildWatched,
        Map.empty,
        null
      )

      evaled match {
        case Left(error) => RunnerState(nestedEvalStates, Some(error, depth))
        case Right(_) => RunnerState(evalState :: nestedEvalStates, None)
      }
    }
  }

  def makeEvaluator(prevStateOpt: Option[RunnerState.Frame],
                    scriptImportGraph: Map[Path, Seq[Path]],
                    baseModule: BaseModule,
                    millClassloaderSigHash: Int,
                    depth: Int) = {
    val bootLogPrefix = "[mill-build] " * depth

    Evaluator(
      config.home,
      recOut(depth),
      recOut(depth),
      baseModule,
      PrefixLogger(logger, "", tickerContext = bootLogPrefix),
      millClassloaderSigHash
    )
      .withWorkerCache(prevStateOpt.map(_.workerCache).getOrElse(Map.empty).to(collection.mutable.Map))
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
