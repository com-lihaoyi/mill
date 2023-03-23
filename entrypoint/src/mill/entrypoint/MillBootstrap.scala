package mill.entrypoint
import mill.util.{Classpath, ColorLogger, Colors, PrintLogger, SystemStreams}
import mill.MillCliConfig
import mill.api.{Logger, PathRef}

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.internal.Watchable
import mill.main.{EvaluatorState, RunScript}
import mill.define.{ScriptNode, SelectMode}

class MillBootstrap(base: os.Path,
                    config: MillCliConfig,
                    streams: SystemStreams,
                    env: Map[String, String],
                    threadCount: Option[Int],
                    systemProperties: Map[String, String],
                    targetsAndParams: Seq[String],
                    ringBell: Boolean,
                    setIdle: Boolean => Unit,
                    stateCache: Option[EvaluatorState],
                    initialSystemProperties: Map[String, String],
                    logger: ColorLogger){

  def runScript(): (Boolean, Option[EvaluatorState]) = {
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


  def evaluate(): (Seq[PathRef], Option[String], Option[EvaluatorState], Boolean) = {

    val millBuildBase = base / "out" / "mill-build"
    val enclosingClasspath: Seq[os.Path] = mill.util.Classpath
      .classpath(getClass.getClassLoader, millBuildBase / "mill-java-rt.jar")


    val selfClassURL = getClass.getProtectionDomain().getCodeSource().getLocation()
    assert(selfClassURL.getProtocol == "file")
    val selfClassLocation = os.Path(selfClassURL.getPath)

    // Copy the current location of the enclosing classes to `mill-launcher.jar`
    // if it has the wrong file extension, because the Zinc incremental compiler
    // doesn't recognize classpath entries without the proper file extension
    val millLauncherOpt =
      if (os.isFile(selfClassLocation) &&
          !Set("zip", "jar", "class").contains(selfClassLocation.ext)){
        os.copy.over(
          selfClassLocation,
          millBuildBase / "mill-launcher.jar",
          createFolders = true
        )
        Some(millBuildBase / "mill-launcher.jar")
      }else None


    val bootstrapModule = new MillBootstrapModule(enclosingClasspath ++ millLauncherOpt, base)

    val millClassloaderSigHash = Classpath.initialClasspathSignature(getClass.getClassLoader).hashCode()
    val evaluator = makeEvaluator(millBuildBase, bootstrapModule, millClassloaderSigHash, Nil)

    val systemPropertiesToUnset =
      stateCache.map(_.setSystemProperties).getOrElse(Set()) -- systemProperties.keySet

    for (k <- systemPropertiesToUnset) {
      initialSystemProperties.get(k) match {
        case None => System.clearProperty(k)
        case Some(original) => System.setProperty(k, original)
      }
    }

    val (bootstrapClassloaderImportTreeOpt, bootstrapWatched: Seq[PathRef]) = stateCache match{
      case Some(s) if watchedSigUnchanged(s.watched) => Right((s.bootstrapClassloader, s.importTree)) -> s.watched
      case _ =>
        RunScript.evaluateTasks(
          evaluator,
          Seq("millbuild.{runClasspath,scriptImportGraph}"),
          mill.define.SelectMode.Separated
        ) match {
          case Left(msg) => (Left(msg), Nil)
          case Right((bootstrapWatched, evaluated)) =>

            evaluated match {
              case Left(msg) => (Left(msg), bootstrapWatched)
              case Right(Seq((runClasspath: Seq[PathRef], _), (scriptImportGraph: Map[String, (os.Path, Seq[String])], _))) =>
                stateCache.map(_.bootstrapClassloader).foreach(_.close())

                val runClassLoader = new java.net.URLClassLoader(
                  runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
                  getClass.getClassLoader
                )

                val processedImportTree = GraphUtils.linksToScriptNodeGraph(scriptImportGraph)
                (Right((runClassLoader, processedImportTree)), bootstrapWatched)
            }
        }
    }

    bootstrapClassloaderImportTreeOpt match{
      case Left(msg) => (bootstrapWatched, Some(msg), None, false)
      case Right((bootstrapClassloader, scriptImportGraph)) =>
        mill.util.Util.withContextClassloader(bootstrapClassloader){

          val cls = bootstrapClassloader.loadClass("millbuild.build$")
          val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]
          val buildFileEvaluator = makeEvaluator(
            base / "out",
            rootModule,
            millClassloaderSigHash,
            scriptImportGraph
          )

          RunScript.evaluateTasks(buildFileEvaluator, targetsAndParams, SelectMode.Separated) match {
            case Left(msg) => (bootstrapWatched, Some(msg), None, false)
            case Right((buildWatched, evaluated)) =>
              val evalState = EvaluatorState(
                buildFileEvaluator.rootModule,
                Nil,
                buildFileEvaluator.workerCache,
                Nil,
                systemProperties.keySet,
                scriptImportGraph,
                bootstrapClassloader
              )

              val buildWatchedPaths = bootstrapWatched ++ buildWatched
              evaluated match {
                case Left(msg) => (buildWatchedPaths, Some(msg), Some(evalState), false)
                case Right(items) => (buildWatchedPaths, None, Some(evalState), true)
              }
          }
        }
    }

  }

  def watchedSigUnchanged(sig: Seq[(mill.internal.Watchable, Long)]) = {
    sig.forall { case (p, l) => p.poll() == l }
  }


  private def makeEvaluator(outPath: os.Path,
                            bootstrapModule: mill.define.BaseModule,
                            sig: Int,
                            scriptImportGraph: Seq[ScriptNode]) = {
    Evaluator(
      config.home,
      outPath,
      outPath,
      bootstrapModule,
      logger,
      sig
    ).withWorkerCache(collection.mutable.Map.empty)
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withScriptImportGraph(scriptImportGraph)
  }
}
