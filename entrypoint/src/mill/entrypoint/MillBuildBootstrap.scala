package mill.entrypoint
import mill.util.{Classpath, ColorLogger, Colors, PrefixLogger, PrintLogger, SystemStreams}
import mill.{BuildInfo, MillCliConfig}
import mill.api.{Logger, PathRef}

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.define.{BaseModule, ScriptNode, SelectMode}
import os.Path

import java.net.URLClassLoader

object MillBuildBootstrap{

  def evaluate(base: os.Path,
               config: MillCliConfig,
               env: Map[String, String],
               threadCount: Option[Int],
               systemProperties: Map[String, String],
               targetsAndParams: Seq[String],
               stateCache: Option[EvaluatorState],
               initialSystemProperties: Map[String, String],
               logger: ColorLogger): Watching.Result[EvaluatorState] = {

    def makeEvaluator(outPath: os.Path,
                      baseModule: mill.define.BaseModule,
                      sig: Int,
                      scriptImportGraph: Seq[ScriptNode],
                      logger: ColorLogger) = {
      Evaluator(config.home, outPath, outPath, baseModule, logger, sig)
        .withWorkerCache(collection.mutable.Map.empty)
        .withEnv(env)
        .withFailFast(!config.keepGoing.value)
        .withThreadCount(threadCount)
        .withScriptImportGraph(scriptImportGraph)
    }

    val projectOut = base / "out"
    val bootProjectOut = projectOut / "mill-build"
    val millBootClasspath = prepareMillBootClasspath(bootProjectOut)
    val bootModule = new MillBuildModule(millBootClasspath, base)

    val millClassloaderSigHash = millBootClasspath
      .map(p => (p, if (os.exists(p)) os.mtime(p) else 0))
      .hashCode()

    val bootLogPrefix = "[mill-build] "

    val bootEvaluator = makeEvaluator(
      bootProjectOut,
      bootModule,
      millClassloaderSigHash,
      Nil,
      PrefixLogger(logger, bootLogPrefix)
    )

    adjustJvmProperties(systemProperties, stateCache, initialSystemProperties)


    val (bootClassloaderImportTreeOpt, bootWatched) = stateCache match {
      case Some(s) if s.watched.forall(_.validate()) =>
        Right((s.bootClassloader, s.importTree)) -> s.watched

      case _ =>
        evaluateWithWatches(bootEvaluator, Seq("{runClasspath,scriptImportGraph}")) {
          case Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[os.Path, Seq[os.Path]]) =>

            stateCache.map(_.bootClassloader).foreach(_.close())

            val runClassLoader = new URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
              getClass.getClassLoader
            )

            val processedImportTree = FileImportGraph.linksToScriptNodeGraph(base, scriptImportGraph)

            (runClassLoader, processedImportTree)
        }
    }

    bootClassloaderImportTreeOpt match {
      case Left(msg) =>
        val prefixedBootMsg = msg.linesIterator.map(bootLogPrefix + _).mkString("\n")
        (bootWatched, Some(prefixedBootMsg), None, false)

      case Right((bootClassloader, scriptImportGraph)) =>
        mill.util.Util.withContextClassloader(bootClassloader) {

          val cls = bootClassloader.loadClass("millbuild.build$")
          val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]
          val buildFileEvaluator = makeEvaluator(
            projectOut,
            rootModule,
            millClassloaderSigHash,
            scriptImportGraph,
            logger
          )

          val (evaled, buildWatched) =
            evaluateWithWatches(buildFileEvaluator, targetsAndParams) { _ => () }

          val buildWatchedPaths = bootWatched ++ buildWatched

          val evalState = EvaluatorState(
            rootModule,
            Nil,
            buildFileEvaluator.workerCache,
            Nil,
            systemProperties.keySet,
            scriptImportGraph,
            bootClassloader
          )
          (buildWatchedPaths, evaled.left.toOption, Some(evalState), evaled.isRight)
        }
    }
  }

  def adjustJvmProperties(systemProperties: Map[String, String],
                          stateCache: Option[EvaluatorState],
                          initialSystemProperties: Map[String, String]): Unit = {
    val systemPropertiesToUnset =
      stateCache.map(_.setSystemProperties).getOrElse(Set()) -- systemProperties.keySet

    for (k <- systemPropertiesToUnset) {
      initialSystemProperties.get(k) match {
        case None => System.clearProperty(k)
        case Some(original) => System.setProperty(k, original)
      }
    }
  }

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
