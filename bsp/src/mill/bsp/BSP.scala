package mill.bsp

import mill.api.{Ctx, PathRef}
import mill.{Agg, T}
import mill.define.{Command, Discover, ExternalModule, Task}
import mill.main.BuildInfo
import mill.eval.Evaluator
import mill.util.Util.millProjectModule
import mill.scalalib.{BoundDep, CoursierModule, Dep, Lib}
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.JavaModuleUtils

object BSP extends ExternalModule with CoursierModule {

  private def evaluator(): Evaluator =
    Option(Evaluator.currentEvaluator.value)
      .orElse(Option(Evaluator.allBootstrapEvaluators.value).flatMap(_.value.headOption))
      .get

  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  private def bspModuleData: T[(Seq[String], Seq[Dep], Seq[String])] = T.input {
    // As this is a runtime value which can change, we need to be in an input target
    val modules = JavaModuleUtils.transitiveModules(evaluator().rootModule)
      .collect { case m: BspModule => m }
    val extensions = modules.flatMap(m => m.bspExtensions).distinct
    val classes = extensions.map(_.className).distinct
    T.log.debug(s"BSP extensions: ${BspUtil.pretty(extensions)}")
    val extensionIvyDeps = extensions.flatMap(_.ivyDeps)

    val languages = modules.flatMap(m => m.bspBuildTarget.languageIds).distinct

    (classes, extensionIvyDeps, languages)
  }

  override def bindDependency: Task[Dep => BoundDep] = T.task { dep: Dep =>
    Lib.depToBoundDep(dep, BuildInfo.scalaVersion, s"_mill${BuildInfo.millBinPlatform}")
  }

  private def bspWorkerLibs: T[Agg[PathRef]] = T {
    millProjectModule(
      "mill-bsp-worker",
      repositoriesTask(),
      extraDeps = bspModuleData()._2.map(bindDependency().andThen(_.dep))
    )
  }

  /**
   * Installs the mill-bsp server. It creates a json file
   * with connection details in the ./.bsp directory for
   * a potential client to find.
   *
   * If a .bsp folder with a connection file already
   * exists in the working directory, it will be
   * overwritten and a corresponding message will be displayed
   * in stdout.
   *
   * If the creation of the .bsp folder fails due to any other
   * reason, the message and stacktrace of the exception will be
   * printed to stdout.
   */
  def install(jobs: Int = 1): Command[(PathRef, ujson.Value)] = T.command {
    // we create a file containing the additional jars to load
//    val libUrls = bspWorkerLibs().map(_.path.toNIO.toUri).iterator.toSeq

    val moduleData = bspModuleData()
    val (classes, _, languages) = moduleData

    val bspServerConfig = BspServerConfig(
      classes,
      bspWorkerLibs().toSeq,
      languages
    )

    val cpFile =
      T.workspace / Constants.bspDir / s"${Constants.serverName}-${BuildInfo.millVersion}.conf"
    os.write.over(
      cpFile,
      upickle.default.write(bspServerConfig, indent = 2),
      createFolders = true
    )
    createBspConnection(jobs, Constants.serverName)
  }

  /**
   * This command only starts a BSP session, which means it injects the current evaluator into an already running BSP server.
   * This command requires Mill to start with `--bsp` option.
   * @param ev The Evaluator
   * @return The server result, indicating if mill should re-run this command or just exit.
   */
  def startSession(allBootstrapEvaluators: Evaluator.AllBootstrapEvaluators)
      : Command[BspServerResult] = T.command {
    T.log.errorStream.println("BSP/startSession: Starting BSP session")
    val res = BspContext.bspServerHandle.runSession(allBootstrapEvaluators.value)
    T.log.errorStream.println(s"BSP/startSession: Finished BSP session, result: ${res}")
    res
  }

  private def createBspConnection(
      jobs: Int,
      serverName: String
  )(implicit ctx: Ctx): (PathRef, ujson.Value) = {
    // we create a json connection file
    val bspFile = ctx.workspace / Constants.bspDir / s"${serverName}.json"
    if (os.exists(bspFile)) ctx.log.info(s"Overwriting BSP connection file: ${bspFile}")
    else ctx.log.info(s"Creating BSP connection file: ${bspFile}")
    val withDebug = ctx.log.debugEnabled
    if (withDebug) ctx.log.debug(
      "Enabled debug logging for the BSP server. If you want to disable it, you need to re-run this install command without the --debug option."
    )
    val connectionContent = bspConnectionJson(jobs, withDebug)
    os.write.over(bspFile, connectionContent, createFolders = true)
    (PathRef(bspFile), upickle.default.read[ujson.Value](connectionContent))
  }

  private def bspConnectionJson(jobs: Int, debug: Boolean): String = {
    val props = sys.props
    val millPath = props
      .get("mill.main.cli")
      // we assume, the classpath is an executable jar here
      .orElse(props.get("java.class.path"))
      .getOrElse(throw new IllegalStateException("System property 'java.class.path' not set"))

    upickle.default.write(
      BspConfigJson(
        name = "mill-bsp",
        argv = Seq(
          millPath,
          "--bsp",
          "--disable-ticker",
          "--color",
          "false",
          "--jobs",
          s"${jobs}"
        ) ++ (if (debug) Seq("--debug") else Seq()),
        millVersion = BuildInfo.millVersion,
        bspVersion = Constants.bspProtocolVersion,
        languages = Constants.languages
      ),
      indent = 2
    )
  }

}
