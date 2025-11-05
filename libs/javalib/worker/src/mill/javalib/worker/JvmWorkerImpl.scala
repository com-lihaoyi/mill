package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.{LaunchedServer, ServerLauncher}
import mill.client.lock.{DoubleLock, Locks, MemoryLock}
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.*
import mill.javalib.internal.JvmWorkerArgs
import mill.javalib.zinc.{ZincApi, ZincWorker}
import mill.util.{CachedFactoryWithInitData, Jvm, RequestId, RequestIdFactory}
import sbt.internal.util.ConsoleOut

import java.time.LocalDateTime

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends JvmWorkerApi with AutoCloseable {
  import args.*

  private val requestIds = RequestIdFactory()

  private def fileLog(s: String)(using requestId: RequestId): Unit =
    os.write.append(
      compilerBridge.workspace / "jvm-worker.log",
      s"[${LocalDateTime.now()}|$requestId] $s\n",
      createFolders = true
    )

  private def fileAndDebugLog(log: Logger.Actions, s: String)(using requestId: RequestId): Unit = {
    fileLog(s)
    log.debug(s)
  }

  /** The local Zinc instance which is used when we do not want to override Java home or runtime options. */
  private val zincLocalWorker = ZincWorker(jobs = jobs)

  override def compileJava(
      op: ZincCompileJava,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    given RequestId = requestIds.next()
    zincApi(javaHome, javaRuntimeOptions).compileJava(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  override def compileMixed(
      op: ZincCompileMixed,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    given RequestId = requestIds.next()
    zincApi(javaHome, javaRuntimeOptions).compileMixed(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  def scaladocJar(
      op: ZincScaladocJar,
      javaHome: Option[os.Path]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    given RequestId = requestIds.next()
    fileLog(pprint.apply(op).render)
    zincApi(javaHome, JavaRuntimeOptions(Seq.empty)).scaladocJar(op)
  }

  override def discoverTests(
      op: mill.javalib.api.internal.ZincDiscoverTests,
      javaHome: Option[os.Path]
  )(using ctx: JvmWorkerApi.Ctx): Seq[String] = {
    given RequestId = requestIds.next()

    zincApi(javaHome, JavaRuntimeOptions(Seq.empty)).discoverTests(op)
  }

  override def getTestTasks(
      op: mill.javalib.api.internal.ZincGetTestTasks,
      javaHome: Option[os.Path]
  )(using
      ctx: JvmWorkerApi.Ctx
  ): Seq[String] = {
    given RequestId = requestIds.next()

    zincApi(javaHome, JavaRuntimeOptions(Seq.empty)).getTestTasks(op)
  }

  override def discoverJunit5Tests(
      op: mill.javalib.api.internal.ZincDiscoverJunit5Tests,
      javaHome: Option[os.Path]
  )(using ctx: JvmWorkerApi.Ctx): Seq[String] = {
    given RequestId = requestIds.next()
    zincApi(javaHome, JavaRuntimeOptions(Seq.empty)).discoverJunit5Tests(op)
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
      ctx: JvmWorkerApi.Ctx,
      requestId: RequestId
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
      import init.log
      given RequestId = init.requestId

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

      fileAndDebugLog(log, s"Checking if $mainClass is already running for $key")
      fileAndDebugLog(log, "Acquiring the launcher lock: " + locks.launcherLock)

      val launched = ServerLauncher.launchOrConnectToServer(
        locks,
        daemonDir.toNIO,
        10 * 1000,
        () => {
          fileAndDebugLog(log, s"Starting JVM subprocess for $mainClass for $key")
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
        fileAndDebugLog(log, _),
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
