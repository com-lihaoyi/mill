package mill.entrypoint
import mill.util.{Classpath, ColorLogger, Colors, PrintLogger, SystemStreams}
import mill.{BuildInfo, MillCliConfig}
import mill.api.{Logger, PathRef}

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.internal.Watchable
import mill.main.{EvaluatorState, RunScript}
import mill.define.{BaseModule, ScriptNode, SelectMode}
import os.Path

import java.net.URLClassLoader

object MillBootstrap{
  type EvaluateResult = (Seq[PathRef], Option[String], Option[EvaluatorState], Boolean)


  def watchLoop(logger: ColorLogger,
                ringBell: Boolean,
                config: MillCliConfig,
                streams: SystemStreams,
                setIdle: Boolean => Unit,
                evaluate: () => EvaluateResult): (Boolean, Option[EvaluatorState]) = {
    while (true) {
      val (watched, errorOpt, resultOpt, isSuccess) = evaluate()

      errorOpt.foreach(logger.error)
      if (ringBell) {
        if (isSuccess) println("\u0007")
        else {
          println("\u0007")
          Thread.sleep(250)
          println("\u0007")
        }
      }

      if (!config.watch.value) {
        return (isSuccess, resultOpt)
      }
      val watchables = watched.map(p => (Watchable.Path(p.path), Watchable.pathSignature(p.path)))
      // If the file changed between the creation of the original
      // `PathRef` and the current moment, use random junk .sig values
      // to force an immediate re-run. Otherwise calculate the
      // pathSignatures the same way Ammonite would and hand over the
      // values, so Ammonite can watch them and only re-run if they
      // subsequently change
      val alreadyStale = watched.exists(p => p.sig != PathRef(p.path, p.quick).sig)
      if (!alreadyStale) {
        Watching.watchAndWait(logger, setIdle, streams.in, watchables)
      }
    }
    ???
  }


  def evaluate(base: os.Path,
               config: MillCliConfig,
               env: Map[String, String],
               threadCount: Option[Int],
               systemProperties: Map[String, String],
               targetsAndParams: Seq[String],
               stateCache: Option[EvaluatorState],
               initialSystemProperties: Map[String, String],
               logger: ColorLogger): MillBootstrap.EvaluateResult = {

    def makeEvaluator(outPath: os.Path,
                      baseModule: mill.define.BaseModule,
                      sig: Int,
                      scriptImportGraph: Seq[ScriptNode]) = {
      Evaluator(config.home, outPath, outPath, baseModule, logger, sig)
        .withWorkerCache(collection.mutable.Map.empty)
        .withEnv(env)
        .withFailFast(!config.keepGoing.value)
        .withThreadCount(threadCount)
        .withScriptImportGraph(scriptImportGraph)
    }

    val millBuildBase = base / "out" / "mill-build"
    val millBootstrapClasspath = prepareMillBootstrapClasspath(millBuildBase)
    val bootstrapModule = new MillBootstrapModule(millBootstrapClasspath, base)

    val millClassloaderSigHash = millBootstrapClasspath
      .map(p => (p, if (os.exists(p)) os.mtime(p) else 0))
      .hashCode()

    val bootstrapEvaluator = makeEvaluator(
      millBuildBase,
      bootstrapModule,
      millClassloaderSigHash,
      Nil
    )

    adjustJvmProperties(systemProperties, stateCache, initialSystemProperties)


    val (bootstrapClassloaderImportTreeOpt, bootstrapWatched: Seq[PathRef]) = stateCache match {
      case Some(s) if watchedSigUnchanged(s.watched) =>
        Right((s.bootstrapClassloader, s.importTree)) -> s.watched

      case _ =>
        evaluateWithWatches(bootstrapEvaluator, Seq("millbuild.{runClasspath,scriptImportGraph}")) {
          case Seq(runClasspath: Seq[PathRef], scriptImportGraph: Map[String, (os.Path, Seq[String])]) =>
            stateCache.map(_.bootstrapClassloader).foreach(_.close())

            val runClassLoader = new URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
              getClass.getClassLoader
            )

            val processedImportTree = GraphUtils.linksToScriptNodeGraph(scriptImportGraph)
            (runClassLoader, processedImportTree)
        }
    }

    bootstrapClassloaderImportTreeOpt match {
      case Left(msg) => (bootstrapWatched, Some(msg), None, false)
      case Right((bootstrapClassloader, scriptImportGraph)) =>
        mill.util.Util.withContextClassloader(bootstrapClassloader) {

          val cls = bootstrapClassloader.loadClass("millbuild.build$")
          val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]
          val buildFileEvaluator = makeEvaluator(
            base / "out",
            rootModule,
            millClassloaderSigHash,
            scriptImportGraph
          )

          val (evaled, buildWatched) =
            evaluateWithWatches(buildFileEvaluator, targetsAndParams) { _ => () }

          val buildWatchedPaths = bootstrapWatched ++ buildWatched

          val evalState = EvaluatorState(
            rootModule,
            Nil,
            buildFileEvaluator.workerCache,
            Nil,
            systemProperties.keySet,
            scriptImportGraph,
            bootstrapClassloader
          )
          (buildWatchedPaths, evaled.left.toOption, Some(evalState), evaled.isRight)
        }
    }
  }

  def watchedSigUnchanged(sig: Seq[(mill.internal.Watchable, Long)]) = {
    sig.forall { case (p, l) => p.poll() == l }
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

  def prepareMillBootstrapClasspath(millBuildBase: Path) = {
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
                            (handleResults: Seq[Any] => T): (Either[String, T], Seq[PathRef]) = {
    RunScript.evaluateTasks(evaluator, targetsAndParams, SelectMode.Separated) match {
      case Left(msg) => (Left(msg), Nil)
      case Right((watched, evaluated)) =>
        evaluated match {
          case Left(msg) => (Left(msg), watched)
          case Right(results) => (Right(handleResults(results.map(_._1))), watched)
        }
    }
  }
}
