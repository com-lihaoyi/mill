package mill.launcher

import mill.api.daemon.MillException
import mill.api.SystemStreams
import mill.api.internal.PathAliasing
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
    if (env.contains("MILL_TEST_EXIT_AFTER_BSP_CHECK")) return 0

    // The launcher does NOT relativize paths: it spawns the daemon/no-daemon processes and resolves
    // their program/cwd/stdout-redirect paths against its *own* cwd before those processes exist, so
    // it can't rely on the `../mill-workspace` symlinks (which live next to the spawned process's
    // cwd). Forcing the raw (real-absolute) serializer here keeps every path the launcher hands to
    // `ProcessBuilder` real, regardless of any `OS_LIB_PATH_RELATIVIZER_BASE` it inherited. The
    // daemon, in contrast, *does* relativize and relies on the symlinks the launcher installs at its
    // fixed cwd-parent.
    PathAliasing.withRawPathSerializer {
      val stderr = streamsOpt.map(_.err).getOrElse(System.err)
      val parsedConfig = MillCliConfig.parse(args).toOption

      val bspServerMode = parsedConfig.exists(_.bsp.value)
      val bspMode = bspServerMode || parsedConfig.exists(_.bspInstall.value)
      val useFileLocks = parsedConfig.exists(_.useFileLocks.value)
      val outMode = if (bspMode) OutFolderMode.BSP else OutFolderMode.REGULAR

      val resolved = mill.internal.OutputDirectoryLayout.resolve(outMode, workDir, env)
      import resolved.{effectiveEnv, outDir, regularOutDir}

      // BSP shares the regular MillDaemonMain by default so build state is reused
      // across CLI and BSP. Opting into a separate BSP output dir (via the
      // `mill-separate-bsp-output-dir: true` build header or `MILL_BSP_OUTPUT_DIR`)
      // also opts back into the prior foreground `MillBspMain` JVM, which owns its
      // own stdio for the lsp4j JSON-RPC connection.
      val bspSeparateOutputDir =
        bspMode && mill.internal.OutputDirectoryLayout.bspOutOverride(workDir, env).isDefined
      val runNoDaemon =
        parsedConfig.exists(_.noDaemonEnabled > 0) || bspSeparateOutputDir

      val logFile = os.Path(outDir, workDir) / "mill-launcher/log"
      val formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))
      def log(s: String) =
        os.write.append(logFile, s"${formatter.format(Instant.now())} $s\n", createFolders = true)

      if (bspServerMode) logBspInfoMessage(outDir, regularOutDir)

      coursier.Resolve.proxySetup()

      // Reproducible builds: tell the daemon where the workspace lives, and configure the os-lib
      // path relativizer so cached output paths serialize via the `out/mill-workspace` /
      // `out/mill-home` aliases. These env vars contribute to the daemon's restart fingerprint,
      // so an existing daemon restarts when the user's workspace changes.
      val workspaceEnv = PathAliasing.workspaceEnvVars(workDir)
      val scopedEnv = effectiveEnv ++ workspaceEnv

      try {
        val millRepositories =
          MillProcessLauncher.loadMillConfig(ConfigConstants.millRepositories, workDir)
        val runnerClasspath = CoursierClient.resolveMillDaemon(regularOutDir, millRepositories)
        // Surface build-header `mill-remote-cache-*` keys as CLI flags, before the user's args
        // so an explicit CLI flag still wins.
        val remoteCacheArgs = Seq(
          ConfigConstants.millRemoteCacheLocation -> "--remote-cache-location",
          ConfigConstants.millRemoteCacheSalt -> "--remote-cache-salt",
          ConfigConstants.millRemoteCacheFilter -> "--remote-cache-filter"
        ).flatMap { case (key, flag) =>
          MillProcessLauncher.loadMillConfig(key, workDir).flatMap(value => Seq(flag, value))
        }
        val optsArgs =
          MillProcessLauncher.loadMillConfig(ConfigConstants.millOpts, workDir) ++
            remoteCacheArgs ++ args

        if (runNoDaemon)
          MillProcessLauncher.launchMillNoDaemon(
            optsArgs,
            outMode,
            runnerClasspath,
            mainClass =
              if (bspSeparateOutputDir) "mill.daemon.MillBspMain"
              else "mill.daemon.MillNoDaemonMain",
            useFileLocks,
            workDir,
            scopedEnv,
            millRepositories,
            streamsOpt = streamsOpt
          )
        else
          runViaDaemon(
            optsArgs,
            outMode,
            outDir,
            runnerClasspath,
            useFileLocks,
            workDir,
            scopedEnv,
            millRepositories,
            streamsOpt,
            stderr,
            log
          )
      } catch {
        case e: MillException =>
          stderr.println(e.getMessage)
          1
        case e =>
          val sw = StringWriter()
          e.printStackTrace(PrintWriter(sw))
          log(sw.toString)
          stderr.println(sw.toString)
          stderr.println(s"Mill launcher failed. See ${logFile.relativeTo(workDir)} for details.")
          1
      }
    }
  }

  private def runViaDaemon(
      optsArgs: Seq[String],
      outMode: OutFolderMode,
      outDir: String,
      runnerClasspath: Seq[os.Path],
      useFileLocks: Boolean,
      workDir: os.Path,
      effectiveEnv: Map[String, String],
      millRepositories: Seq[String],
      streamsOpt: Option[SystemStreams],
      stderr: java.io.PrintStream,
      log: String => Unit
  ): Int = {
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

    // Retry if server requests it. This can happen when:
    // - There's a version mismatch between client and server
    // - The server was terminated while this client was waiting
    val maxRetries = 10
    var retries = 0
    var exitCode = launcher.run(daemonDir, javaHome, log)
    while (exitCode == ClientUtil.ServerExitPleaseRetry && retries < maxRetries) {
      exitCode = launcher.run(daemonDir, javaHome, log)
      retries += 1
    }
    if (exitCode == ClientUtil.ServerExitPleaseRetry)
      stderr.println(s"Max launcher retries exceeded ($maxRetries), exiting")
    exitCode
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
