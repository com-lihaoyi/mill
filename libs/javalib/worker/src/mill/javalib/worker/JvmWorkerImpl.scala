package mill.javalib.worker

import mill.api.daemon.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.{LaunchedServer, ServerLauncher}
import mill.client.lock.{DoubleLock, Locks, MemoryLock}
import mill.constants.{DaemonFiles, EnvVars}
import mill.javalib.api.internal.*
import mill.javalib.api.JvmWorkerArgs
import mill.javalib.zinc.{ZincApi, ZincWorker}
import mill.util.{CachedFactoryBase, Jvm}
import sbt.internal.util.ConsoleOut

import java.nio.file.FileSystemException

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends InternalJvmWorkerApi with AutoCloseable {
  import args.*

  /** The local Zinc instance which is used when we do not want to override Java home or runtime options. */
  private val zincLocalWorker = ZincWorker(jobs = jobs, useFileLocks = useFileLocks)

  override def apply(
      op: ZincOp,
      javaHome: Option[os.Path],
      javaRuntimeOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: InternalJvmWorkerApi.Ctx): op.Response = {
    val log = ctx.log
    val workspaceRoot = mill.api.BuildCtx.workspaceRoot
    val taskDest = ctx.dest
    val zincCtx = ZincWorker.LocalConfig(
      dest = taskDest,
      logDebugEnabled = log.debugEnabled,
      logPromptColored = log.prompt.colored,
      workspaceRoot = workspaceRoot
    )

    val zincApi =
      if (javaRuntimeOptions.isEmpty && javaHome.isEmpty) localZincApi(zincCtx, log)
      else new SubprocessZincApi(
        javaHome,
        javaRuntimeOptions,
        zincCtx,
        log,
        subprocessCache,
        compilerBridge
      )

    zincApi.apply(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  override def close(): Unit = {
    zincLocalWorker.close()
    subprocessCache.close()
    close0() // make sure this is invoked last as it closes the classloader that we need for other `.close` calls
  }

  private val subprocessCache: CachedFactoryBase[
    SubprocessZincApi.Key,
    SubprocessZincApi.Key,
    SubprocessZincApi.Initialize,
    SubprocessZincApi.Value
  ] = {
    var memoryLocksByDaemonDir = Map.empty[os.Path, MemoryLock]
    def memLockFor(daemonDir: os.Path): MemoryLock = {
      memoryLocksByDaemonDir.synchronized {
        memoryLocksByDaemonDir.get(daemonDir) match {
          case Some(lock) => lock
          case None =>
            val lock = MemoryLock()
            memoryLocksByDaemonDir = memoryLocksByDaemonDir.updated(daemonDir, lock)
            lock
        }
      }
    }

    new CachedFactoryBase[
      SubprocessZincApi.Key,
      SubprocessZincApi.Key,
      SubprocessZincApi.Initialize,
      SubprocessZincApi.Value
    ] {
      def keyToInternalKey(key: SubprocessZincApi.Key): SubprocessZincApi.Key = key
      def maxCacheSize: Int = args.jobs
      def shareValues: Boolean = true

      def setup(
          key: SubprocessZincApi.Key,
          internalKey: SubprocessZincApi.Key,
          init: SubprocessZincApi.Initialize
      ): SubprocessZincApi.Value = {
        val taskDest = init.taskDest
        val workerDir = taskDest / "zinc-worker" / key.hashCode.toString
        val daemonDir = workerDir / "daemon"
        val daemonDirAbs = daemonDir.wrapped.toAbsolutePath.normalize().toString
        val workspaceAbs = init.workspaceRoot.wrapped.toAbsolutePath.normalize().toString
        val homeAbs = os.home.wrapped.toAbsolutePath.normalize().toString
        val aliasOut = daemonDir / "out"
        val aliasOutSuffix = aliasOut.segments.toVector.takeRight(2)
        val workspaceAlias = aliasOut / "mill-workspace"
        val homeAlias = aliasOut / "mill-home"
        def linkExists(link: os.Path): Boolean =
          java.nio.file.Files.exists(link.toNIO, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        def ensureSymlink(link: os.Path, dest: os.Path): Unit = {
          if (!linkExists(link)) {
            try os.symlink(link, dest)
            catch {
              case _: java.nio.file.FileAlreadyExistsException =>
                if (!linkExists(link)) throw new java.nio.file.FileAlreadyExistsException(link.toString)
            }
          }
        }

        mill.api.BuildCtx.withFilesystemCheckerDisabled {
          os.makeDir.all(daemonDir)
          if (aliasOutSuffix != Seq("out", "mill-workspace") && aliasOutSuffix != Seq(
                "out",
                "mill-home"
              )) {
            os.makeDir.all(aliasOut)
            ensureSymlink(workspaceAlias, init.workspaceRoot)
            ensureSymlink(homeAlias, os.home)
          }
        }
        os.write.over(workerDir / "java-home", key.javaHome.map(_.toString).getOrElse("<default>"))
        os.write.over(workerDir / "java-runtime-options", key.runtimeOptions.mkString("\n"))

        val mainClass = "mill.javalib.worker.MillJvmWorkerMain"
        val baseLocks = Locks.forDirectory(daemonDirAbs, useFileLocks)
        val locks = {
          Locks(
            // File locks are non-reentrant, so we need to lock on the memory lock first.
            //
            // We can get multiple lock acquisitions when we compile several modules in parallel,
            DoubleLock(memLockFor(daemonDir), baseLocks.launcherLock),
            // We never take the daemon lock, just check if it's already taken
            baseLocks.daemonLock
          )
        }

        val suppressArgs = Jvm.getJvmSuppressionArgs(key.javaHome)

        val launched = ServerLauncher.launchOrConnectToServer(
          locks,
          daemonDir,
          10 * 1000,
          () => {
            val process = Jvm.spawnProcess(
              mainClass = mainClass,
              mainArgs = Seq(daemonDirAbs, jobs.toString, useFileLocks.toString),
              javaHome = key.javaHome,
              jvmArgs = key.runtimeOptions ++ suppressArgs,
              classPath = classPath,
              env = Map(
                EnvVars.MILL_WORKSPACE_ROOT -> workspaceAbs,
                EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> s"$workspaceAbs,out/mill-workspace;$homeAbs,out/mill-home"
              )
            )
            LaunchedServer.OsProcess(process.wrapped.toHandle)
          },
          processDied =>
            throw IllegalStateException(
              s"""Failed to launch '$mainClass' for:
                 |  javaHome = ${key.javaHome}
                 |  runtimeOptions = ${key.runtimeOptions.mkString(",")}
                 |  daemonDir = $daemonDir
                 |
                 |Failure:
                 |$processDied
                 |""".stripMargin
            ),
          _ => (),
          false, // openSocket
          config = ServerLauncher.DaemonConfig.empty
        )

        SubprocessZincApi.Value(launched.port, daemonDir, launched.launchedServer, locks)
      }

      def teardown(
          key: SubprocessZincApi.Key,
          internalKey: SubprocessZincApi.Key,
          value: SubprocessZincApi.Value
      ): Unit = {
        os.remove(value.daemonDir / DaemonFiles.processId)
        while (value.launchedServer.isAlive) Thread.sleep(1)

        try value.lock.close()
        catch { case _ => () }
        // On Windows it takes some time until the file handles are released, so we have
        // to wait for that as well.
        if (scala.util.Properties.isWin) {
          val daemonLock = value.daemonDir / DaemonFiles.daemonLock

          def tryRemoving(): Boolean = {
            try { os.remove.all(daemonLock); true }
            catch {
              case _: FileSystemException => false
            }
          }

          while (!tryRemoving()) Thread.sleep(10)
        }
      }
    }
  }

  /** Gives you API for the [[zincLocalWorker]] instance. */
  private def localZincApi(ctx: ZincWorker.LocalConfig, log: Logger): ZincApi = {
    val deps = ZincWorker.ProcessConfig(
      log = log,
      consoleOut = ConsoleOut.printStreamOut(log.streams.err),
      compilerBridge
    )

    new ZincApi {
      def apply(
          op: ZincOp,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean
      ): op.Response = {
        zincLocalWorker.apply(op, reporter, reportCachedProblems, ctx, deps)
      }
    }
  }
}
