package mill.bsp

import mill.api.{Ctx, PathRef}
import mill.{T, Task, given}
import mill.define.{Command, Discover, Evaluator, ExternalModule}
import mill.main.BuildInfo
import mill.scalalib.{CoursierModule, Dep}

object BSP extends ExternalModule with CoursierModule {

  lazy val millDiscover = Discover[this.type]

  private def bspWorkerLibs: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-bsp-worker")))
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
  def install(jobs: Int = 1): Command[(PathRef, ujson.Value)] = Task.Command {
    // we create a file containing the additional jars to load
    val libUrls = bspWorkerLibs().map(_.path.toNIO.toUri.toURL).iterator.toSeq
    val cpFile =
      Task.workspace / Constants.bspDir / s"${Constants.serverName}-${BuildInfo.millVersion}.resources"
    os.write.over(
      cpFile,
      libUrls.mkString("\n"),
      createFolders = true
    )
    createBspConnection(jobs, Constants.serverName)
  }

  private def createBspConnection(
      jobs: Int,
      serverName: String
  )(implicit ctx: Ctx): (PathRef, ujson.Value) = {
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

}
