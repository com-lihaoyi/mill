package mill.entrypoint
import mill.util.{Classpath, ColorLogger, Colors, PrintLogger}
import mill.MillCliConfig
import mill.api.{Logger, PathRef}

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.internal.Watchable
import mill.main.{EvaluatorState, RunScript}
import mill.define.SelectMode

class MillBootstrap(config: MillCliConfig,
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

    val bootstrapModule = new MillBootstrapModule(
      mill.util.Classpath
        .classpath(getClass.getClassLoader, None)
        .map(_.toURI).filter(_.getScheme == "file")
        .map(_.getPath)
        .map(os.Path(_)),
      os.pwd / "mill-build"
    )

    val evaluator = makeEvaluator(os.pwd / "out" / "mill-build", bootstrapModule, Nil)

    val systemPropertiesToUnset =
      stateCache.map(_.setSystemProperties).getOrElse(Set()) -- systemProperties.keySet

    for (k <- systemPropertiesToUnset) {
      initialSystemProperties.get(k) match {
        case None => System.clearProperty(k)
        case Some(original) => System.setProperty(k, original)
      }
    }

    val (bootstrapClassloaderOpt, bootstrapWatched: Seq[PathRef]) = stateCache match{
      case Some(s) if watchedSigUnchanged(s.watched) => Right(s.bootstrapClassloader) -> s.watched
      case _ =>
        RunScript.evaluateTasks(
          evaluator,
          Seq("millbuild.runClasspath"),
          mill.define.SelectMode.Separated
        ) match {
          case Left(msg) => (Left(msg), Nil)
          case Right((bootstrapWatched, evaluated)) =>
            evaluated match {
              case Left(msg) => (Left(msg), bootstrapWatched)
              case Right(Seq((runClasspath: Seq[PathRef], _))) =>
                stateCache.map(_.bootstrapClassloader).foreach(_.close())

                val runClassLoader = new java.net.URLClassLoader(
                  runClasspath.map(_.path.toNIO.toUri.toURL).toArray
                )

                (Right(runClassLoader), bootstrapWatched)
            }
        }
    }

    bootstrapClassloaderOpt match{
      case Left(msg) => (bootstrapWatched, Some(msg), None, false)
      case Right(bootstrapClassloader) =>
        mill.util.Util.withContextClassloader(bootstrapClassloader){

          val cls = bootstrapClassloader.loadClass("millbuild.build$")
          val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]
          val buildFileEvaluator = makeEvaluator(
            os.pwd / "out",
            rootModule,
            Classpath.initialClasspathSignature(bootstrapClassloader)
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
                Nil,
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
                            sig: Seq[(Either[String, java.net.URL], Long)]) = {
    Evaluator(
      config.home,
      outPath,
      outPath,
      bootstrapModule,
      logger
    ).withClassLoaderSig(sig)
      .withWorkerCache(collection.mutable.Map.empty)
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withImportTree(Nil)
  }
}
