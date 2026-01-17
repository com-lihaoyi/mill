package mill.launcher

import mill.client._
import mill.constants.{ConfigConstants, EnvVars, OutFiles, OutFolderMode}
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

  val p = new mill.constants.Profiler()
  def main(args: Array[String]): Unit = {

    val parsedConfig = MillCliConfig.parse(args).toOption

    val bspMode = parsedConfig.exists(c => c.bsp.value || c.bspInstall.value)
    val useFileLocks = parsedConfig.exists(_.useFileLocks.value)

    // Ensure that if we're running in BSP mode we don't start a daemon.
    //
    // This is needed because when Metals/Idea closes, they only kill the BSP client and the BSP
    // server lurks around waiting for the next client to connect.
    // This is unintuitive from the user's perspective and wastes resources, as most people expect
    // everything related to the BSP server to be killed when closing the editor.
    val runNoDaemon = parsedConfig.exists(_.noDaemonEnabled > 0) || bspMode

    val outMode = if (bspMode) OutFolderMode.BSP else OutFolderMode.REGULAR
    if (System.getenv("MILL_TEST_EXIT_AFTER_BSP_CHECK") != null) System.exit(0)
    val outDir = OutFiles.OutFiles.outFor(outMode)
    val logFile = os.Path(outDir, os.pwd) / "mill-launcher/log"
    val formatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))
    val log: String => Unit =
      s =>
        os.write.append(logFile, s"${formatter.format(Instant.now())} $s\n", createFolders = true)
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

    val runnerClasspath = CoursierClient.resolveMillDaemon()
    try {
      if (runNoDaemon) {
        val mainClass = if (bspMode) "mill.daemon.MillBspMain" else "mill.daemon.MillNoDaemonMain"
        val exitCode = MillProcessLauncher.launchMillNoDaemon(
          args.toSeq,
          outMode,
          runnerClasspath,
          mainClass,
          useFileLocks
        )
        System.exit(exitCode)
      } else {
        // start in client-server mode
        val optsArgs = MillProcessLauncher.loadMillConfig(ConfigConstants.millOpts) ++ args
        val launcher = new MillServerLauncher(
          stdout = System.out,
          stderr = System.err,
          env = System.getenv().asScala.toMap,
          args = optsArgs,
          forceFailureForTestingMillisDelay = -1,
          useFileLocks = useFileLocks,
          initServerFactory = (daemonDir, _) =>
            new LaunchedServer.OsProcess(
              MillProcessLauncher.launchMillDaemon(
                daemonDir,
                outMode,
                runnerClasspath,
                useFileLocks
              ).toHandle
            )
        )

        val daemonDir = os.Path(outDir, os.pwd) / OutFiles.OutFiles.millDaemon
        val javaHome = MillProcessLauncher.javaHome()

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
      }
    } catch {
      case e: mill.api.daemon.MillException =>
        System.err.println(e.getMessage)
        System.exit(1)
      case e =>
        val sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))

        log(sw.toString)

        System.err.println(s"Mill launcher failed. See ${logFile.relativeTo(os.pwd)} for details.")
        System.exit(1)
    }
  }
}
