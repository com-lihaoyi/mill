package mill.bsp

import java.io.{InputStream, PrintStream}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import mill.api.{Ctx, DummyInputStream, Logger, PathRef, Result, SystemStreams}
import mill.{Agg, T, BuildInfo => MillBuildInfo}
import mill.define.{Command, Discover, ExternalModule, Task}
import mill.eval.Evaluator
import mill.util.Util.millProjectModule
import mill.scalalib.{CoursierModule, Dep}
import mill.util.PrintLogger
import os.Path

object BSP extends ExternalModule with CoursierModule {

  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  private def bspWorkerLibs: T[Agg[PathRef]] = T {
    millProjectModule("mill-bsp-worker", repositoriesTask())
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
    val libUrls = bspWorkerLibs().map(_.path.toNIO.toUri.toURL).iterator.toSeq
    val cpFile =
      T.workspace / Constants.bspDir / s"${Constants.serverName}-${mill.BuildInfo.millVersion}.resources"
    os.write.over(
      cpFile,
      libUrls.mkString("\n"),
      createFolders = true
    )
    BspWorker(T.workspace, T.home, T.log, Some(libUrls))
      .map(_.createBspConnection(jobs, Constants.serverName))
  }

  /**
   * This command only starts a BSP session, which means it injects the current evaluator into an already running BSP server.
   * This command requires Mill to start with `--bsp` option.
   * @param ev The Evaluator
   * @return The server result, indicating if mill should re-run this command or just exit.
   */
  def startSession(ev: Evaluator): Command[BspServerResult] = T.command {
    T.log.errorStream.println("BSP/startSession: Starting BSP session")
    val serverHandle: BspServerHandle =
      Await.result(BspContext.bspServerHandle.future, Duration.Inf)
    val res = serverHandle.runSession(ev)
    T.log.errorStream.println(s"BSP/startSession: Finished BSP session, result: ${res}")
    res
  }
  def createBspConnection(
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

  def bspConnectionJson(jobs: Int, debug: Boolean): String = {
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
        millVersion = MillBuildInfo.millVersion,
        bspVersion = Constants.bspProtocolVersion,
        languages = Constants.languages
      )
    )
  }

}
