package mill.entrypoint
import ammonite.util.Colors
import mill.MillCliConfig
import mill.api.PathRef

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.internal.Watchable
import mill.main.{EvaluatorState, RunScript}
import mill.util.PrintLogger
import mill.define.SelectMode

import scala.annotation.tailrec
import scala.collection.immutable.Seq
object MillBootstrap{

  def runScript(config: MillCliConfig,
                mainInteractive: Boolean,
                stdin: InputStream,
                stdout: PrintStream,
                stderr: PrintStream,
                env: Map[String, String],
                threadCount: Option[Int],
                systemProperties: Map[String, String],
                targetsAndParams: Seq[String],
                ringBell: Boolean,
                setIdle: Boolean => Unit): (Boolean, Option[EvaluatorState]) = {
    while (true) {
      val (watched, errorOpt, resultOpt, isSuccess) = evaluate(
        config,
        mainInteractive,
        stdin,
        stdout,
        stderr,
        env,
        threadCount,
        systemProperties,
        targetsAndParams
      )

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
        Watching.watchAndWait(setIdle, stdin, watchables)
      }
    }
    ???
  }


  def evaluate(config: MillCliConfig,
               mainInteractive: Boolean,
               stdin: InputStream,
               stdout: PrintStream,
               stderr: PrintStream,
               env: Map[String, String],
               threadCount: Option[Int],
               systemProperties: Map[String, String],
               targetsAndParams: Seq[String]): (Seq[PathRef], Option[String], Option[EvaluatorState], Boolean) = {
    val bootstrapModule = new MillBootstrapModule(
      ammonite.util.Classpath
        .classpath(getClass.getClassLoader, None)
        .map(_.toURI).filter(_.getScheme == "file")
        .map(_.getPath)
        .map(os.Path(_)),
      os.pwd / "mill-build"
    )

    val colored = config.color.getOrElse(mainInteractive)
    val colors = if (colored) ammonite.util.Colors.Default else ammonite.util.Colors.BlackWhite
    val logger = makeLogger(config, stdin, stdout, stderr, colored, colors)
    val evaluator = makeEvaluator(os.pwd / "out" / "mill-build", config, env, threadCount, bootstrapModule, logger)

    RunScript.evaluateTasks(
      evaluator,
      Seq("runClasspath"),
      mill.define.SelectMode.Separated
    ) match{
      case Left(msg) => (Nil, Some(msg), None, false)
      case Right((bootstrapWatched, evaluated)) =>
        evaluated match{
          case Left(msg) => (bootstrapWatched, Some(msg), None, false)
          case Right(Seq((runClasspath: Seq[PathRef], _))) =>
            val runClassLoader = new java.net.URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray
            )
            val cls = runClassLoader.loadClass("millbuild.MillBuildModule$")
            val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]

            val evalState = EvaluatorState(
              rootModule, Nil, collection.mutable.Map.empty, Nil, systemProperties.keySet, Nil
            )
            val evaluator2 = makeEvaluator(os.pwd / "out", config, env, threadCount, rootModule, logger)

            RunScript.evaluateTasks(evaluator2, targetsAndParams, SelectMode.Separated) match{
              case Left(msg) => (bootstrapWatched, Some(msg), Some(evalState), false)
              case Right((buildWatched, evaluated)) =>

                val buildWatchedPaths = bootstrapWatched ++ buildWatched
                evaluated match{
                  case Left(msg) => (buildWatchedPaths, Some(msg), Some(evalState), false)
                  case Right(items) => (buildWatchedPaths, None, Some(evalState), true)
                }

            }

        }

    }

  }

  private def makeLogger(config: MillCliConfig, stdin: InputStream, stdout: PrintStream, stderr: PrintStream, colored: Boolean, colors: Colors) = {
    mill.util.PrintLogger(
      colored = colored,
      disableTicker = config.disableTicker.value,
      infoColor = colors.info(),
      errorColor = colors.error(),
      outStream = stdout,
      infoStream = stderr,
      errStream = stderr,
      inStream = stdin,
      debugEnabled = config.debugLog.value,
      context = ""
    )
  }

  private def makeEvaluator(outPath: os.Path,
                            config: MillCliConfig,
                            env: Map[String, String],
                            threadCount: Option[Int],
                            bootstrapModule: mill.define.BaseModule,
                            logger: PrintLogger) = {
    Evaluator(
      config.home,
      outPath,
      outPath,
      bootstrapModule,
      logger
    ).withClassLoaderSig(Nil)
      .withWorkerCache(collection.mutable.Map.empty)
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withImportTree(Nil)
  }
}
