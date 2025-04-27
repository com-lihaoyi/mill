package mill.bsp

import mill.util.BuildInfo
import mill.define.WorkspaceRoot

import java.io.PrintStream

private[mill] object BSP {

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
    val bspFile = WorkspaceRoot.workspaceRoot / Constants.bspDir / s"${Constants.serverName}.json"
    if (os.exists(bspFile)) errStream.println(s"Overwriting BSP connection file: ${bspFile}")
    else errStream.println(s"Creating BSP connection file: ${bspFile}")
    if (withDebug) errStream.println(
      "Enabled debug logging for the BSP server. If you want to disable it, you need to re-run this install command without the --debug option."
    )
    val connectionContent = bspConnectionJson(jobs, withDebug)
    os.write.over(bspFile, connectionContent, createFolders = true)
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

  def defaultJobCount: Int = 1
}
