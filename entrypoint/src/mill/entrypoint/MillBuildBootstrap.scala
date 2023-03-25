package mill.entrypoint
import mill.util.{ColorLogger, PrefixLogger, Util}
import mill.{BuildInfo, MillCliConfig}
import mill.api.{PathRef, internal}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.define.{BaseModule, SelectMode}
import os.Path

import java.net.URLClassLoader

@internal
class MillBuildBootstrap(projectRoot: os.Path,
                         config: MillCliConfig,
                         env: Map[String, String],
                         threadCount: Option[Int],
                         targetsAndParams: Seq[String],
                         stateCache: MultiEvaluatorState,
                         logger: ColorLogger){

  val millBootClasspath = MillBuildBootstrap.prepareMillBootClasspath(projectRoot / "out")

  def evaluate(): Watching.Result[MultiEvaluatorState] = {

    val multiState = evaluateRec(0)
    Watching.Result(
      watched = multiState.evalStates.flatMap(_.watched),
      error = multiState.errorAndDepth.map(_._1),
      result = multiState
    )
  }

  def evaluateRec(depth: Int): MultiEvaluatorState = {

    val prevStateOpt = stateCache
      .evalStates
      .lift(depth - stateCache.errorAndDepth.map(_._2).getOrElse(0))

    val recEither =
      if (os.exists(recRoot(depth) / "build.sc")) Right(evaluateRec(depth + 1))
      else Left(new MillBuildModule(millBootClasspath, recRoot(depth)))

    val buildModule = recEither match {
      case Left(millBuildModule) => millBuildModule
      case Right(metaMultiEvaluatorState) => metaMultiEvaluatorState.evalStates.last.buildModule
    }

    val evaluator = makeEvaluator(prevStateOpt, Map.empty, buildModule, 0, depth)

    if (depth != 0) processRunClasspath(depth, prevStateOpt, 0, evaluator)
    else Util.withContextClassloader(buildModule.getClass.getClassLoader) {
      val (evaled, buildWatched) =
        MillBuildBootstrap.evaluateWithWatches(evaluator, targetsAndParams) { _ => () }

      val evalState = EvaluatorState(
        evaluator.workerCache.toMap,
        buildWatched,
        Map.empty,
        null
      )

      val previousEvalStates = recEither.toSeq.flatMap(_.evalStates)
      evaled match {
        case Left(error) => MultiEvaluatorState(previousEvalStates, Some(error, depth))
        case Right(_) => MultiEvaluatorState(previousEvalStates ++ Seq(evalState), None)
      }
    }
  }

  def makeEvaluator(prevStateOpt: Option[EvaluatorState],
                    scriptImportGraph: Map[Path, Seq[Path]],
                    baseModule: BaseModule, 
                    millClassloaderSigHash: Int,
                    recDepth: Int) = {
    val bootLogPrefix = "[mill-build] " * recDepth
    Evaluator(
      config.home,
      recOut(recDepth),
      recOut(recDepth),
      baseModule,
      PrefixLogger(logger, bootLogPrefix),
      millClassloaderSigHash
    )
      .withWorkerCache(prevStateOpt.map(_.workerCache).getOrElse(Map.empty).to(collection.mutable.Map))
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withScriptImportGraph(scriptImportGraph)
  }


  def recRoot(recDepth: Int) = projectRoot / Seq.fill(recDepth)("mill-build")
  def recOut(recDepth: Int) = projectRoot / "out" / Seq.fill(recDepth)("mill-build")

  def processRunClasspath(recDepth: Int,
                          prevStateOpt: Option[EvaluatorState],
                          millClassloaderSigHash: Int,
                          evaluator: Evaluator): MultiEvaluatorState = {
    val (bootClassloaderImportGraphOpt, bootWatched) = prevStateOpt match {
      case Some(s) if s.watched.forall(_.validate()) =>
        Right((s.classLoader, s.scriptImportGraph)) -> s.watched

      case _ =>
        MillBuildBootstrap.evaluateWithWatches(evaluator, Seq("{runClasspath,scriptImportGraph}")) {
          case Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[Path, Seq[Path]]) =>
            prevStateOpt.foreach(_.classLoader.close())
            val runClassLoader = new URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
              getClass.getClassLoader
            )

            (runClassLoader, scriptImportGraph)
        }
    }

    bootClassloaderImportGraphOpt match {
      case Left(error) =>
        MultiEvaluatorState(Nil, Some(error, recDepth))
      case Right((classLoaderOpt, scriptImportGraph)) =>
        val evalState = EvaluatorState(
          evaluator.workerCache.toMap,
          bootWatched,
          scriptImportGraph,
          classLoaderOpt
        )

        MultiEvaluatorState(List(evalState), None)
    }
  }
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
