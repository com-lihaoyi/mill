package mill.launcher

import mill.api.daemon.MillException
import mill.api.SystemStreams
import mill.client.*
import mill.constants.{ConfigConstants, EnvVars, OutFiles, OutFolderMode}
import mill.internal.MillCliConfig

import java.io.{PrintWriter, StringWriter}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

/**
 * Mill launcher main entry point.
 */
object MillLauncherMain {
  def main(args: Array[String]): Unit = {
    System.exit(main0(args, None, sys.env, os.pwd))
  }

  /**
   * Version of `main` we can call in-memory for use in tests
   */
  def main0(
      args: Array[String],
      streamsOpt: Option[SystemStreams],
      env: Map[String, String],
      workDir: os.Path
  ): Int = {
    val stderr = streamsOpt.map(_.err).getOrElse(System.err)

    val parsedConfig = MillCliConfig.parse(args).toOption

    val bspServerMode = parsedConfig.exists(_.bsp.value)
    val bspMode = bspServerMode || parsedConfig.exists(_.bspInstall.value)
    val useFileLocks = parsedConfig.exists(_.useFileLocks.value)

    val runNoDaemon = parsedConfig.exists(c => c.noDaemonEnabled > 0 || c.bspInstall.value)

    val outMode = if (bspMode) OutFolderMode.BSP else OutFolderMode.REGULAR
    if (env.contains("MILL_TEST_EXIT_AFTER_BSP_CHECK")) return 0
    val effectiveEnv = MillProcessLauncher.effectiveEnvForOutMode(outMode, workDir, env)
    val outDir = MillProcessLauncher.outDir(outMode, workDir, effectiveEnv)
    val regularOutDir = MillProcessLauncher.regularOutDir(effectiveEnv)
    val logFile = os.Path(outDir, workDir) / "mill-launcher/log"
    val formatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))

    def log(s: String) =
      os.write.append(logFile, s"${formatter.format(Instant.now())} $s\n", createFolders = true)

    if (bspServerMode) logBspInfoMessage(outDir, regularOutDir)

    coursier.Resolve.proxySetup()

    try {
      val millRepositories =
        MillProcessLauncher.loadMillConfig(ConfigConstants.millRepositories, workDir)

      val runnerClasspath = CoursierClient.resolveMillDaemon(regularOutDir, millRepositories)
      val optsArgs = MillProcessLauncher.loadMillConfig(ConfigConstants.millOpts, workDir) ++ args
      if (runNoDaemon) {
        val mainClass =
          if (bspServerMode) "mill.daemon.MillBspMain" else "mill.daemon.MillNoDaemonMain"
        MillProcessLauncher.launchMillNoDaemon(
          optsArgs,
          outMode,
          runnerClasspath,
          mainClass,
          useFileLocks,
          workDir,
          effectiveEnv,
          millRepositories
        )
      } else { // start in client-server mode
        val jvmOpts = MillProcessLauncher.computeJvmOpts(workDir, effectiveEnv)
        val launcher = new MillServerLauncher(
          streamsOpt = streamsOpt,
          env = effectiveEnv,
          args = optsArgs,
          forceFailureForTestingMillisDelay = -1,
          useFileLocks = useFileLocks,
          initServerFactory = (daemonDir, _) =>
            LaunchedServer.OsProcess(
              MillProcessLauncher.launchMillDaemon(
                daemonDir,
                outMode,
                runnerClasspath,
                useFileLocks,
                workDir,
                effectiveEnv,
                millRepositories
              ).wrapped.toHandle
            ),
          jvmOpts = jvmOpts,
          millRepositories = millRepositories
        )

        val daemonDir = os.Path(outDir, workDir) / OutFiles.OutFiles.millDaemon
        val javaHome = MillProcessLauncher.javaHome(effectiveEnv, workDir, millRepositories)

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
          stderr.println(s"Max launcher retries exceeded ($maxRetries), exiting")
        }
        exitCode
      }
    } catch {
      case e: MillException =>
        stderr.println(e.getMessage)
        1
      case e =>
        val sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))

        log(sw.toString)

        stderr.println(sw.toString)
        stderr.println(s"Mill launcher failed. See ${logFile.relativeTo(workDir)} for details.")
        1
    }
  }

  private def logBspInfoMessage(outDir: String, regularOutDir: String): Unit = {
    val message = if (outDir == regularOutDir) {
      s"Mill is running in BSP mode, sharing the regular '$outDir' output directory " +
        "and daemon/task caches with CLI builds. Set `mill-separate-bsp-output-dir: true` " +
        s"in the root build header or '${EnvVars.MILL_BSP_OUTPUT_DIR}' to use a dedicated BSP " +
        "output directory instead."
    } else {
      s"Mill is running in BSP mode, using the dedicated output directory '$outDir'. " +
        s"Remove `mill-separate-bsp-output-dir: true` from the root build header or unset " +
        s"'${EnvVars.MILL_BSP_OUTPUT_DIR}' to share the regular '$regularOutDir' output directory " +
        "and daemon/task caches with CLI builds."
    }
    System.err.println(message)

  }
}
