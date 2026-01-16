package mill.launcher

import mill.client._
import mill.constants.{BuildInfo, EnvVars, OutFiles, OutFolderMode}
import mill.internal.MillCliConfig

import java.io.{PrintWriter, StringWriter}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/**
 * Mill launcher main entry point.
 */
object MillLauncherMain {

  def main(args: Array[String]): Unit = {
    var runNoDaemon = false
    var bspMode = false
    var useFileLocks = false

    MillCliConfig.parse(args).toOption match {
      case Some(config) =>
        if (config.bsp.value || config.bspInstall.value) bspMode = true
        if (config.noDaemonEnabled > 0) runNoDaemon = true
        if (config.useFileLocks.value) useFileLocks = true
      case None =>
    }

    // Ensure that if we're running in BSP mode we don't start a daemon.
    //
    // This is needed because when Metals/Idea closes, they only kill the BSP client and the BSP
    // server lurks around waiting for the next client to connect.
    // This is unintuitive from the user's perspective and wastes resources, as most people expect
    // everything related to the BSP server to be killed when closing the editor.
    if (bspMode) runNoDaemon = true

    val outMode = if (bspMode) OutFolderMode.BSP else OutFolderMode.REGULAR
    exitInTestsAfterBspCheck()
    val outDir = OutFiles.OutFiles.outFor(outMode)

    if (outMode == OutFolderMode.BSP) {
      val message = if (OutFiles.OutFiles.mergeBspOut) {
        s"Mill is running in BSP mode and '${EnvVars.MILL_NO_SEPARATE_BSP_OUTPUT_DIR}' environment variable " +
          s"is set, Mill will use the regular '$outDir' as the output directory. Unset this environment variable if you" +
          " want to use a separate output directory for BSP. This will increase" +
          " the CPU usage of the BSP server but make it more responsive."
      } else {
        s"Mill is running in BSP mode, using a separate output directory '$outDir'. " +
          s"If you would like to reuse the regular `out/` directory, set the " +
          s"'${EnvVars.MILL_NO_SEPARATE_BSP_OUTPUT_DIR}' environment variable. This will reduce the CPU usage " +
          "of the BSP server but make it less responsive."
      }
      System.err.println(message)
    }

    coursier.Resolve.proxySetup()

    val runnerClasspath = MillProcessLauncher.cachedComputedValue0(
      outMode,
      "resolve-runner",
      BuildInfo.millVersion,
      () => CoursierClient.resolveMillDaemon(),
      value => value.forall(s => os.exists(os.Path(s)))
    )

    if (runNoDaemon) {
      try {
        val mainClass = if (bspMode) "mill.daemon.MillBspMain" else "mill.daemon.MillNoDaemonMain"
        val exitCode = MillProcessLauncher.launchMillNoDaemon(
          args.toSeq,
          outMode,
          runnerClasspath,
          mainClass,
          useFileLocks
        )
        System.exit(exitCode)
      } catch {
        case e: mill.api.daemon.MillException =>
          // Print clean error message without stack trace for expected errors
          System.err.println(e.getMessage)
          System.exit(1)
      }
    } else {
      val logs = ArrayBuffer[String]()
      try {
        // start in client-server mode
        val optsArgs = MillProcessLauncher.millOpts(outMode) ++ args
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))
        val log: String => Unit = s => logs += s"${formatter.format(Instant.now())} $s"

        val launcher = new MillServerLauncher(
          stdout = System.out,
          stderr = System.err,
          env = System.getenv().asScala.toMap,
          args = optsArgs,
          forceFailureForTestingMillisDelay = -1,
          useFileLocks = useFileLocks,
          initServerFactory = (daemonDir, _) =>
            new LaunchedServer.OsProcess(
              MillProcessLauncher.launchMillDaemon(daemonDir, outMode, runnerClasspath, useFileLocks).toHandle
            )
        )

        val daemonDir = os.Path(outDir, os.pwd) / OutFiles.OutFiles.millDaemon
        val javaHome = MillProcessLauncher.javaHome(outMode)

        MillProcessLauncher.prepareMillRunFolder(daemonDir)
        var exitCode = launcher.run(daemonDir, javaHome, log)

        // Retry if server requests it. This can happen when:
        // - There's a version mismatch between client and server
        // - The server was terminated while this client was waiting
        val maxRetries = 10
        var retries = 0
        while (exitCode == ClientUtil.ServerExitPleaseRetry && retries < maxRetries) {
          exitCode = launcher.run(daemonDir, javaHome, log)
          retries += 1
        }

        if (exitCode == ClientUtil.ServerExitPleaseRetry) {
          System.err.println(s"Max launcher retries exceeded ($maxRetries), exiting")
        }
        System.exit(exitCode)
      } catch {
        case e: Exception =>
          handleLauncherException(e, outDir, logs.toSeq)
          System.exit(1)
      }
    }
  }

  private def exitInTestsAfterBspCheck(): Unit = {
    if (System.getenv("MILL_TEST_EXIT_AFTER_BSP_CHECK") != null) {
      System.exit(0)
    }
  }

  private def handleLauncherException(e: Exception, outDir: String, logs: Seq[String]): Unit = {
    // For MillException, just print the message without stack trace
    e match {
      case _: mill.api.daemon.MillException =>
        System.err.println(e.getMessage)
        return
      case _ =>
    }

    val errorFile = os.Path(outDir, os.pwd) / "mill-launcher-error.log"
    try {
      val sw = new StringWriter()
      e.printStackTrace(new PrintWriter(sw))

      val content =
        "Mill launcher failed with unknown exception.\n\n" +
          "Exception:\n" +
          sw.toString +
          "\nLogs:\n" +
          logs.map(_ + "\n").mkString

      os.write.over(errorFile, content)
    } catch {
      case writeEx: Exception =>
        // If we can't write to file, fall back to stderr
        System.err.println(s"Mill launcher failed. Could not write error log: $writeEx")
        e.printStackTrace()
    }
    System.err.println(s"Mill launcher failed. See $errorFile for details.")
  }
}
