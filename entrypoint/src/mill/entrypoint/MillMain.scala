package mill.entrypoint
import mill.BuildInfo
import mill.MillCliConfigParser
import java.io.{FileOutputStream, InputStream, PrintStream}
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Properties, Success, Try}
import io.github.retronym.java9rtexport.Export
import mainargs.Flag
import mill.api.DummyInputStream
import mill.eval.Evaluator
import mill.main.{BspServerHandle, BspServerResult, BspServerStarter, EvaluatorState}

import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal

object MillMain {

  private[mill] class SystemStreams(val out: PrintStream, val err: PrintStream, val in: InputStream)
  private[mill] val initialSystemStreams = new SystemStreams(System.out, System.err, System.in)

  def main(args: Array[String]): Unit = {
    // setup streams
    val openStreams =
      if (args.headOption == Option("--bsp")) {
        val stderrFile = os.pwd / ".bsp" / "mill-bsp.stderr"
        os.makeDir.all(stderrFile / os.up)
        val err = new PrintStream(new FileOutputStream(stderrFile.toIO, true))
        System.setErr(err)
        System.setOut(err)
        err.println(s"Mill in BSP mode, version ${BuildInfo.millVersion}, ${new java.util.Date()}")
        Seq(err)
      } else Seq()

    if (Properties.isWin && System.console() != null)
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    val (result, _) =
      try {
        main0(
          args,
          None,
          ammonite.util.Util.isInteractive(),
          System.in,
          System.out,
          System.err,
          System.getenv().asScala.toMap,
          b => (),
          systemProperties = Map(),
          initialSystemProperties = sys.props.toMap
        )
      } finally {
        System.setOut(initialSystemStreams.out)
        System.setErr(initialSystemStreams.err)
        System.setIn(initialSystemStreams.in)
        openStreams.foreach(_.close())
      }
    System.exit(if (result) 0 else 1)
  }

  def main0(
      args: Array[String],
      stateCache: Option[EvaluatorState],
      mainInteractive: Boolean,
      stdin: InputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      systemProperties: Map[String, String],
      initialSystemProperties: Map[String, String]
  ): (Boolean, Option[EvaluatorState]) = {

    MillCliConfigParser.parse(args) match {
      // Cannot parse args
      case Left(msg) =>
        stderr.println(msg)
        (false, None)

      case Right(config) if config.help.value =>
        stdout.println(MillCliConfigParser.usageText)
        (true, None)

      case Right(config) if config.showVersion.value =>
        def p(k: String, d: String = "<unknown>") = System.getProperty(k, d)
        stdout.println(
          s"""Mill Build Tool version ${BuildInfo.millVersion}
             |Java version: ${p("java.version", "<unknown Java version")}, vendor: ${p(
              "java.vendor",
              "<unknown Java vendor"
            )}, runtime: ${p("java.home", "<unknown runtime")}
             |Default locale: ${Locale.getDefault()}, platform encoding: ${p(
              "file.encoding",
              "<unknown encoding>"
            )}
             |OS name: "${p("os.name")}", version: ${p("os.version")}, arch: ${p(
              "os.arch"
            )}""".stripMargin
        )
        (true, None)

      case Right(config)
          if (
            config.interactive.value || config.repl.value || config.noServer.value || config.bsp.value
          ) && stdin == DummyInputStream =>
        // because we have stdin as dummy, we assume we were already started in server process
        stderr.println(
          "-i/--interactive/--repl/--no-server/--bsp must be passed in as the first argument"
        )
        (false, None)

      case Right(config)
          if Seq(
            config.interactive.value,
            config.repl.value,
            config.noServer.value,
            config.bsp.value
          ).count(identity) > 1 =>
        stderr.println(
          "Only one of -i/--interactive, --repl, --no-server or --bsp may be given"
        )
        (false, None)

      case Right(config) =>
        if (!config.silent.value) {
          checkMillVersionFromFile(os.pwd, stderr)
        }

        val useRepl =
          config.repl.value || (config.interactive.value && config.leftoverArgs.value.isEmpty)

        // special BSP mode, in which we spawn a server and register the current evaluator when-ever we start to eval a dedicated command
        val bspMode = config.bsp.value && config.leftoverArgs.value.isEmpty

        val (success, nextStateCache) =
          if (config.repl.value && config.leftoverArgs.value.nonEmpty) {
            stderr.println("No target may be provided with the --repl flag")
            (false, stateCache)
//          } else if(config.bsp.value && config.leftoverArgs.value.nonEmpty) {
//            stderr.println("No target may be provided with the --bsp flag")
//            (false, stateCache)
          } else if (config.leftoverArgs.value.isEmpty && config.noServer.value) {
            stderr.println(
              "A target must be provided when not starting a build REPL"
            )
            (false, stateCache)
          } else if (useRepl && stdin == DummyInputStream) {
            stderr.println(
              "Build REPL needs to be run with the -i/--interactive/--repl flag"
            )
            (false, stateCache)
          } else {
            if (useRepl && config.interactive.value) {
              stderr.println(
                "WARNING: Starting a build REPL without --repl is deprecated"
              )
            }
            val systemProps =
              systemProperties ++ config.extraSystemProperties

            val threadCount = config.threadCountRaw match {
              case None => Some(1)
              case Some(0) => None
              case Some(n) => Some(n)
            }

            val predefCode =
              if (!useRepl) ""
              else
                s"""import $$file.build, build._
                   |implicit val replApplyHandler = mill.main.ReplApplyHandler(
                   |  os.Path(${pprint.apply(
                    config.home.toIO.getCanonicalPath.replace("$", "$$")
                  ).plainText}),
                   |  ${config.disableTicker.value},
                   |  interp.colors(),
                   |  repl.pprinter(),
                   |  build.millSelf.get,
                   |  build.millDiscover,
                   |  debugLog = ${config.debugLog.value},
                   |  keepGoing = ${config.keepGoing.value},
                   |  systemProperties = ${systemProps.toSeq
                    .map(p => s""""${p._1}" -> "${p._2}"""")
                    .mkString("Map[String,String](", ",", ")")},
                   |  threadCount = ${threadCount}
                   |)
                   |repl.pprinter() = replApplyHandler.pprinter
                   |""".stripMargin

            val importsPredefCode: String = config.imports.map {
              _.split("[:]", 2) match {
                case Array("ivy", dep) =>
                  s"""import $$ivy.`${dep}`"""
                case x => throw new Exception(s"Unsupported plugin declaration: '$x'.")
              }
            }.mkString("\n")

            val ammConfig = ammonite.main.Config(
              core = ammonite.main.Config.Core(
                noDefaultPredef = config.noDefaultPredef,
                silent = config.silent,
                watch = config.watch,
                bsp = Flag(),
                code = None,
                home = config.home,
                predefFile = config.predefFile,
                color = config.color,
                thin = Flag(),
                help = config.help,
                showVersion = Flag()
              ),
              predef = ammonite.main.Config.Predef(
                predefCode = Seq(predefCode, importsPredefCode).filter(_.nonEmpty).mkString("\n"),
                noHomePredef = Flag()
              ),
              repl = ammonite.main.Config.Repl(
                banner = MillCliConfigParser.customName,
                noRemoteLogging = Flag(),
                classBased = Flag()
              )
            )


            if (mill.main.client.Util.isJava9OrAbove) {
              val rt = config.home / Export.rtJarName
              if (!os.exists(rt)) {
                println(
                  s"Preparing Java ${System.getProperty("java.version")} runtime; this may take a minute or two ..."
                )
                Export.rtTo(rt.toIO, false)
              }
            }

            if (useRepl) {
              println("Loading...")
              ???
            } else {
              class BspContext {
                // BSP mode, run with a simple evaluator command to inject the evaluator
                // The command returns when the server exists or the workspace should be reloaded
                // if the `lastResult` is `ReloadWorkspace` we re-run the script in a loop

                //              import scala.concurrent.ExecutionContext.Implicits._
                val serverThreadContext =
                  ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

                stderr.println("Running in BSP mode with hardcoded startSession command")

                val bspServerHandle = Promise[BspServerHandle]()

                stderr.println("Trying to load BSP server...")
                val bspServerFuture = Future {
                  try {
                    BspServerStarter().startBspServer(
                      initialEvaluator = None,
                      outStream = MillMain.initialSystemStreams.out,
                      errStream = System.err,
                      inStream = MillMain.initialSystemStreams.in,
                      workspaceDir = os.pwd,
                      ammoniteHomeDir = ammConfig.core.home,
                      canReload = true,
                      serverHandle = Some(bspServerHandle)
                    )
                  } catch {
                    case NonFatal(e) =>
                      stderr.println(s"Could not start BSP server. ${e.getMessage}")
                      e.printStackTrace(stderr)
                      BspServerResult.Failure
                  }
                }(serverThreadContext)

                val handle = Await.result(bspServerHandle.future, Duration.Inf).tap { _ =>
                  stderr.println("BSP server started")
                }

                val millArgs = List("mill.bsp.BSP/startSession")
              }

              val bspContext = if (bspMode) Some(new BspContext()) else None
              val targetsAndParams =
                bspContext.map(_.millArgs).getOrElse(config.leftoverArgs.value.toList)

              var repeatForBsp = true
              var loopRes: (Boolean, Option[EvaluatorState]) = (false, None)
              while (repeatForBsp) {
                repeatForBsp = false

//                val runnerRes = r

                val enclosingClasspath = ammonite.util.Classpath
                  .classpath(getClass.getClassLoader, None)
                  .map(_.toURI).filter(_.getScheme == "file")
                  .map(_.getPath)
                  .map(os.Path(_))
                object BootstrapModule
                  extends mill.define.BaseModule(os.pwd)(implicitly, implicitly, implicitly, implicitly, mill.define.Caller(()))
                  with mill.scalalib.ScalaModule {
                    implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
                    def scalaVersion = "2.13.10"
                    def generatedSources = mill.define.Target.input{
                      val top =
                        s"""
                           |package millbuild
                           |import _root_.mill._
                           |object MillBuildModule
                           |extends _root_.mill.define.BaseModule(os.Path("${os.pwd}"))(
                           |  implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())
                           |)
                           |with MillBuildModule{
                           |  // Stub to make sure Ammonite has something to call after it evaluates a script,
                           |  // even if it does nothing...
                           |  def $$main() = Iterator[String]()
                           |
                           |  // Need to wrap the returned Module in Some(...) to make sure it
                           |  // doesn't get picked up during reflective child-module discovery
                           |  def millSelf = Some(this)
                           |
                           |  @_root_.scala.annotation.nowarn("cat=deprecation")
                           |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
                           |}
                           |
                           |sealed trait MillBuildModule extends _root_.mill.main.MainModule{
                           |""".stripMargin
                      val bottom = "\n}"


                      os.write(
                        mill.define.Target.dest / "Build.scala",
                        top + os.read(os.pwd / "build.sc") + bottom
                      )
                      Seq(mill.api.PathRef(mill.define.Target.dest / "Build.scala"))
                    }
                    def unmanagedClasspath = mill.define.Target.input{
                      mill.api.Loose.Agg.from(enclosingClasspath.map(p => mill.api.PathRef(p)))
                    }
                }
                val colored = ammConfig.core.color.getOrElse(mainInteractive)
                val colors = if (colored) ammonite.util.Colors.Default else ammonite.util.Colors.BlackWhite
                val logger = mill.util.PrintLogger(
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
                val evaluator = Evaluator(
                  ammConfig.core.home,
                  os.pwd / "out" / "mill-build",
                  os.pwd / "out" / "mill-build",
                  BootstrapModule,
                      logger
                ).withClassLoaderSig(Nil)
                  .withWorkerCache(collection.mutable.Map.empty)
                  .withEnv(env)
                  .withFailFast(!config.keepGoing.value)
                  .withThreadCount(threadCount)
                  .withImportTree(Nil)

                val evaluated = mill.main.RunScript.evaluateTasks(
                  evaluator,
                  Seq("runClasspath"),
                  mill.define.SelectMode.Separated
                )

                println("XXX")
                val e = evaluated
                  .toOption
                  .get
                  ._2
                  .toOption
                  .get
                  .head
                  ._1
                  .asInstanceOf[Seq[mill.api.PathRef]]
                  .map(_.path)
                println(e)
                val runClassLoader = new java.net.URLClassLoader(e.map(_.toNIO.toUri.toURL).toArray)
                val cls = runClassLoader.loadClass("millbuild.MillBuildModule$")
                val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]
//                println("millBuildModule " + millBuildModule.getClass)
//                println("millBuildModule " + millBuildModule.asInstanceOf[mill.define.BaseModule])

                val evalState = EvaluatorState(
                  rootModule, Nil, collection.mutable.Map.empty, Nil, systemProperties.keySet, Nil
                )
                val evaluator2 = Evaluator(
                  ammConfig.core.home,
                  os.pwd / "out",
                  os.pwd / "out",
                  rootModule,
                  logger
                ).withClassLoaderSig(Nil)
                  .withWorkerCache(collection.mutable.Map.empty)
                  .withEnv(env)
                  .withFailFast(!config.keepGoing.value)
                  .withThreadCount(threadCount)
                  .withImportTree(Nil)

                val evaluated2 = mill.main.RunScript.evaluateTasks(evaluator2, targetsAndParams, mill.define.SelectMode.Separated)
                println("evaluated2 " + evaluated2)
                bspContext.foreach { ctx =>
                  repeatForBsp = ctx.handle.lastResult == Some(BspServerResult.ReloadWorkspace)
                  stderr.println(
                    s"`${ctx.millArgs.mkString(" ")}` returned with ${ctx.handle.lastResult}"
                  )
                }
                loopRes = (true, Some(evalState))
              } // while repeatForBsp
              bspContext.foreach { ctx =>
                stderr.println(
                  s"Exiting BSP runner loop. Stopping BSP server. Last result: ${ctx.handle.lastResult}"
                )
                ctx.handle.stop()
              }
              loopRes
            }
          }
        if (config.ringBell.value) {
          if (success) println("\u0007")
          else {
            println("\u0007")
            Thread.sleep(250)
            println("\u0007")
          }
        }
        (success, nextStateCache)
    }
  }

  private def checkMillVersionFromFile(projectDir: os.Path, stderr: PrintStream) = {
    Seq(
      projectDir / ".config" / "mill-version",
      projectDir / ".mill-version"
    ).collectFirst {
      case f if os.exists(f) =>
        (f, os.read.lines(f).filter(l => l.trim().nonEmpty).headOption)
    }.foreach { case (file, Some(version)) =>
      if (BuildInfo.millVersion != version) {
        val msg =
          s"""Mill version ${BuildInfo.millVersion} is different than configured for this directory!
             |Configured version is ${version} (${file})""".stripMargin
        stderr.println(
          msg
        )
      }
    }
  }

}
