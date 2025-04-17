package mill.bsp

import mill.define.{ModuleCtx, PathRef}
import mill.{T, Task, given}
import mill.define.{Command, Discover, Evaluator, ExternalModule, Mirrors}
import mill.util.BuildInfo
import mill.scalalib.{CoursierModule, Dep}
import Mirrors.autoMirror
import mill.api.internal.{BspServerResult, internal}
import mill.define.WorkspaceRoot

object BSP extends ExternalModule with CoursierModule {
  lazy val millDiscover = Discover[this.type]

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
  def install(jobs: Int = 1): Command[(PathRef, ujson.Value)] = Task.Command {
    createBspConnection(jobs, Constants.serverName)
  }

  private def createBspConnection(
      jobs: Int,
      serverName: String
  )(implicit ctx: mill.define.TaskCtx): (PathRef, ujson.Value) = {
    // we create a json connection file
    val bspFile = ctx.workspace / Constants.bspDir / s"${serverName}.json"
    if (os.exists(bspFile)) ctx.log.warn(s"Overwriting BSP connection file: ${bspFile}")
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
    val millPath = sys.env.get("MILL_MAIN_CLI")
      .orElse(sys.props.get("mill.main.cli"))
      // we assume, the classpath is an executable jar here
      .orElse(sys.props.get("java.class.path"))
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
      )
    )
  }

  private[mill] case class InstallArgs(jobs: Int = 1)
  private[mill] lazy val installArgsParser: mainargs.ParserForClass[InstallArgs] =
    mainargs.ParserForClass[InstallArgs]
  private[mill] def installHelper(args: InstallArgs, withDebug: Boolean): Unit = {
    // we create a json connection file
    val bspFile = WorkspaceRoot.workspaceRoot / Constants.bspDir / s"${Constants.serverName}.json"
    if (os.exists(bspFile)) System.err.println(s"Overwriting BSP connection file: ${bspFile}")
    else System.err.println(s"Creating BSP connection file: ${bspFile}")
    if (withDebug) System.err.println(
      "Enabled debug logging for the BSP server. If you want to disable it, you need to re-run this install command without the --debug option."
    )
    val connectionContent = bspConnectionJson(args.jobs, withDebug)
    os.write.over(bspFile, connectionContent, createFolders = true)
  }
}
