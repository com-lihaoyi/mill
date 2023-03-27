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
//    println(s"+evaluateRec($depth) " + recRoot(depth))
    val prevStateOpt = stateCache
      .evalStates
      .lift(depth + stateCache.errorAndDepth.map(_._2).getOrElse(0))

    val recEither =
      if (os.exists(recRoot(depth) / "build.sc")) Right(evaluateRec(depth + 1))
      else Left{
        val bootRoot = recRoot(depth)
        new MillBuildModule.BootstrapModule(projectRoot, bootRoot, millBootClasspath)(
          mill.runner.BaseModule.Info(bootRoot)
        )
      }

    val buildModuleOrError = recEither match {
      case Left(millBuildModule) => Right((millBuildModule, Map.empty[os.Path, Seq[os.Path]], 0))
      case Right(metaRunnerState) =>
        if (metaRunnerState.errorAndDepth.isDefined) Left(metaRunnerState)
        else {
          val nestedEvalState = metaRunnerState.evalStates.head
          Right((
            nestedEvalState.buildModule,
            nestedEvalState.scriptImportGraph,
            metaRunnerState.evalStates.dropRight(1).headOption.map(_.buildHash).getOrElse(0)
          ))
        }
    }

    val res = buildModuleOrError match{
      case Left(errorState) => errorState
      case Right((buildModule0, scriptImportGraph, buildSigHash)) =>

//        pprint.log(buildSigHash)
        val buildModule = buildModule0
          .millModuleDirectChildren
          .collectFirst{case b: BaseModule => b}
          .getOrElse(buildModule0)

        val evaluator = makeEvaluator(
          prevStateOpt,
          scriptImportGraph,
          buildModule,
          buildSigHash,
          depth
        )

        val nestedEvalStates = recEither.toSeq.toList.flatMap(_.evalStates)

        if (depth != 0) {
          processRunClasspath(depth, nestedEvalStates, evaluator, prevStateOpt)
        } else {
          processFinalTargets(depth, nestedEvalStates, evaluator, buildModule)
        }
    }
//    println(s"-evaluateRec($depth) " + recRoot(depth))
    res
  }


  def processRunClasspath(depth: Int,
                          nestedEvalStates: List[RunnerState.Frame],
                          evaluator: Evaluator,
                          prevStateOpt: Option[RunnerState.Frame]): RunnerState = {
    prevStateOpt match {
      // Check whether the buildModule has been re-evaluated since we last
      // saw it. If the build module hasn't re-evaluated, we can skip
      // evaluating anything and just use the cached data since it will all
      // evaluate to the same values anyway
      case Some(s) if prevStateOpt.exists(_.buildModule eq evaluator.rootModule) =>
        RunnerState(s :: nestedEvalStates, None)
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
        } match {
          case (Left(error), watches) =>
            val evalState = RunnerState.Frame(
              Map.empty,
              watches,
              Map.empty,
              null
            )
            RunnerState(evalState :: nestedEvalStates, Some(error, depth))
          case (Right((classloader, scriptImportGraph)), watches) =>
            val evalState = RunnerState.Frame(
              evaluator.workerCache.toMap,
              watches,
              scriptImportGraph,
              classloader
            )

            RunnerState(evalState :: nestedEvalStates, None)
        }
    }
  }

  def processFinalTargets(depth: Int,
                          nestedEvalStates: List[RunnerState.Frame],
                          evaluator: Evaluator,
                          buildModule: BaseModule): RunnerState = {

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
