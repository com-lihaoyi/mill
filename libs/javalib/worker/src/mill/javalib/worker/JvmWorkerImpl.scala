package mill.javalib.worker

import mill.api.daemon.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.{LaunchedServer, ServerLauncher}
import mill.client.lock.{DoubleLock, Locks, MemoryLock}
import mill.constants.DaemonFiles
import mill.javalib.api.internal.*
import mill.javalib.api.JvmWorkerArgs
import mill.javalib.zinc.{ZincApi, ZincWorker}
import mill.util.{Jvm, RefCountedCache}
import sbt.internal.util.ConsoleOut

import java.nio.file.FileSystemException

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends InternalJvmWorkerApi with AutoCloseable {
  import args.*

  /** The local Zinc instance which is used when we do not want to override Java home or runtime options. */
  private val zincLocalWorker = ZincWorker(jobs = jobs)

  override def apply(
      op: ZincOp,
      javaHome: Option[os.Path],
      javaRuntimeOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: InternalJvmWorkerApi.Ctx): op.Response = {
    val log = ctx.log
    val zincCtx = ZincWorker.LocalConfig(
      dest = ctx.dest,
      logDebugEnabled = log.debugEnabled,
      logPromptColored = log.prompt.colored,
      workspaceRoot = mill.api.BuildCtx.workspaceRoot
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

  private val subprocessCache: RefCountedCache[
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

    new RefCountedCache[
      SubprocessZincApi.Key,
      SubprocessZincApi.Key,
      SubprocessZincApi.Initialize,
      SubprocessZincApi.Value
    ](
      convertKey = identity,
      setup = (key, _, init) => {
        val workerDir = init.taskDest / "zinc-worker" / key.hashCode.toString
        val daemonDir = workerDir / "daemon"

        os.makeDir.all(daemonDir)
        os.write.over(workerDir / "java-home", key.javaHome.map(_.toString).getOrElse("<default>"))
        os.write.over(workerDir / "java-runtime-options", key.runtimeOptions.mkString("\n"))

        val mainClass = "mill.javalib.zinc.ZincWorkerMain"
        val locks = {
          val fileLocks = Locks.files(daemonDir.toString)
          Locks(
            // File locks are non-reentrant, so we need to lock on the memory lock first.
            //
            // We can get multiple lock acquisitions when we compile several modules in parallel,
            DoubleLock(memLockFor(daemonDir), fileLocks.launcherLock),
            // We never take the daemon lock, just check if it's already taken
            fileLocks.daemonLock
          )
        }

        val launched = ServerLauncher.launchOrConnectToServer(
          locks,
          daemonDir.toNIO,
          10 * 1000,
          () => {
            val process = Jvm.spawnProcess(
              mainClass = mainClass,
              mainArgs = Seq(daemonDir.toString, jobs.toString),
              javaHome = key.javaHome,
              jvmArgs = key.runtimeOptions,
              classPath = classPath
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
          false // openSocket
        )

        SubprocessZincApi.Value(launched.port, daemonDir, launched.launchedServer, launched.locks)
      },
      closeValue = value => {
        os.remove(value.daemonDir / DaemonFiles.processId)
        while (value.launchedServer.isAlive) Thread.sleep(1)

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
    )
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
