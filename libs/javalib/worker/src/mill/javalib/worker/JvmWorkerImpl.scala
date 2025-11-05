package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.{LaunchedServer, ServerLauncher}
import mill.client.lock.{DoubleLock, Locks, MemoryLock}
import mill.javalib.api.internal.*
import mill.javalib.internal.JvmWorkerArgs
import mill.javalib.zinc.{ZincApi, ZincWorker}
import mill.util.{CachedFactoryWithInitData, Jvm}
import sbt.internal.util.ConsoleOut

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends JvmWorkerApi with AutoCloseable {
  import args.*

  /** The local Zinc instance which is used when we do not want to override Java home or runtime options. */
  private val zincLocalWorker = ZincWorker(jobs = jobs)

  override def apply(
      op: ZincOperation,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): op.Response = {
    zincApi(javaHome, javaRuntimeOptions)
      .apply(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  override def close(): Unit = {
    zincLocalWorker.close()
    subprocessCache.close()
    close0() // make sure this is invoked last as it closes the classloader that we need for other `.close` calls
  }

  /**
   * Returns the [[ZincApi]] for either the local Zinc instance or the remote Zinc instance depending on the java
   * home and javac options.
   */
  private def zincApi(
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions
  )(using
      ctx: JvmWorkerApi.Ctx
  ): ZincApi = {
    val log = ctx.log
    val zincCtx = ZincWorker.InvocationContext(
      env = ctx.env,
      dest = ctx.dest,
      logDebugEnabled = log.debugEnabled,
      logPromptColored = log.prompt.colored,
      zincLogDebug = zincLogDebug
    )

    if (javaRuntimeOptions.options.isEmpty && javaHome.isEmpty) localZincApi(zincCtx, log)
    else new SubprocessZincApi(
      javaHome,
      javaRuntimeOptions,
      zincCtx,
      log,
      subprocessCache,
      compilerBridge
    )
  }

  private val subprocessCache = new CachedFactoryWithInitData[
    SubprocessCacheKey,
    SubprocessCacheInitialize,
    SubprocessCacheValue
  ] {
    override def maxCacheSize: Int = jobs

    override def cacheEntryStillValid(
        key: SubprocessCacheKey,
        initData: => SubprocessCacheInitialize,
        value: SubprocessCacheValue
    ): Boolean = value.isRunning()

    private var memoryLocksByDaemonDir = Map.empty[os.Path, MemoryLock]
    private def memLockFor(daemonDir: os.Path): MemoryLock = {
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

    override def setup(
        key: SubprocessCacheKey,
        init: SubprocessCacheInitialize
    ): SubprocessCacheValue = {

      val workerDir = init.taskDest / "zinc-worker" / key.sha256
      val daemonDir = workerDir / "daemon"

      os.makeDir.all(daemonDir)
      os.write.over(workerDir / "java-home", key.javaHome.map(_.toString).getOrElse("<default>"))
      os.write.over(workerDir / "java-runtime-options", key.runtimeOptions.options.mkString("\n"))

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
            jvmArgs = key.runtimeOptions.options,
            classPath = classPath
          )
          LaunchedServer.OsProcess(process.wrapped.toHandle)
        },
        processDied =>
          throw IllegalStateException(
            s"""Failed to launch '$mainClass' for:
               |  javaHome = ${key.javaHome}
               |  runtimeOptions = ${key.runtimeOptions.options.mkString(",")}
               |  daemonDir = $daemonDir
               |
               |Failure:
               |$processDied
               |""".stripMargin
          ),
        _ => (),
        false // openSocket
      )

      SubprocessCacheValue(launched.port, daemonDir, launched.launchedServer)
    }

    override def teardown(key: SubprocessCacheKey, value: SubprocessCacheValue): Unit = {
      value.killProcess()
    }
  }

  /** Gives you API for the [[zincLocalWorker]] instance. */
  private def localZincApi(zincCtx: ZincWorker.InvocationContext, log: Logger): ZincApi = {
    val zincDeps = ZincWorker.InvocationDependencies(
      log = log,
      consoleOut = ConsoleOut.printStreamOut(log.streams.err),
      compilerBridge
    )

    zincLocalWorker.api(zincCtx, zincDeps)
  }
}
