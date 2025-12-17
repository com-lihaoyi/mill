package mill.bsp

import mill.util.BuildInfo
import mill.api.BuildCtx

import java.io.PrintStream

object BSP {

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
  def install(jobs: Int, withDebug: Boolean, errStream: PrintStream): Unit = {
    // we create a json connection file
    val bspFile = BuildCtx.workspaceRoot / Constants.bspDir / s"${Constants.serverName}.json"
    if (os.exists(bspFile)) errStream.println(s"Overwriting BSP connection file: ${bspFile}")
    else errStream.println(s"Creating BSP connection file: ${bspFile}")
    if (withDebug) errStream.println(
      "Enabled debug logging for the BSP server. If you want to disable it, you need to re-run this install command without the --debug option."
    )
    val connectionContent = bspConnectionJson(jobs, withDebug)
    os.write.over(bspFile, connectionContent, createFolders = true)
  }

  private def bspConnectionJson(jobs: Int, debug: Boolean): String = {
    val millPath = sys.env.get("MILL_EXECUTABLE_PATH")
      .getOrElse(throw new IllegalStateException("Env 'MILL_EXECUTABLE_PATH' not set"))

    upickle.write(
      BspConfigJson(
        name = "mill-bsp",
        argv = Seq(
          millPath,
          "--bsp",
          "--ticker",
          "false",
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

  def defaultJobCount: Int = 1
}
