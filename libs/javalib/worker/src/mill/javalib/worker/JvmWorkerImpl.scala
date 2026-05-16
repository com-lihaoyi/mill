package mill.javalib.worker

import mill.api.daemon.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.{LaunchedServer, ServerLauncher}
import mill.client.lock.{DoubleLock, Locks, MemoryLock}
import mill.constants.DaemonFiles
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
  private val zincLocalWorker = ZincWorker(
    jobs = jobs,
    useFileLocks = useFileLocks,
    classLoaderCache = sharedClassLoaderCache
  )

  override def apply(
      op: ZincOp,
      javaHome: Option[os.Path],
      javaRuntimeOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: InternalJvmWorkerApi.Ctx): op.Response = {
    val log = ctx.log

    // Worker bytecode is Java 17, so for user JDKs older than 17 we host the worker
    // subprocess on Mill's daemon JVM and have it fork `javac`/`javadoc` to the
    // user's binaries. `-release N` is also injected into scalac options so symbols
    // resolve against the user's JDK, not the worker JVM's, stdlib.
    val forkJavaRelease: Option[Int] = javaHome.flatMap { home =>
      val major = Jvm.getJavaMajorVersion(Some(home))
      Option.when(major > 0 && major < 17)(major)
    }

    // For Java < 17, host the worker subprocess in Mill's daemon JVM (which is
    // Java 17+ by definition) rather than the user's older JVM.
    val workerJavaHome = if (forkJavaRelease.isDefined) None else javaHome

    val zincCtx = ZincWorker.LocalConfig(
      dest = ctx.dest,
      logDebugEnabled = log.debugEnabled,
      logPromptColored = log.prompt.colored,
      workspaceRoot = mill.api.BuildCtx.workspaceRoot,
      forkJavaHome = if (forkJavaRelease.isDefined) javaHome else None
    )

    val zincApi =
      if (javaHome.isEmpty && javaRuntimeOptions.isEmpty) localZincApi(zincCtx, log)
      else SubprocessZincApi(
        workerJavaHome,
        javaRuntimeOptions,
        zincCtx,
        log,
        subprocessCache,
        compilerBridge
      )

    val effectiveOp = forkJavaRelease.fold(op)(JvmWorkerImpl.withScalaRelease(op, _))
    zincApi.apply(
      effectiveOp.asInstanceOf[op.type],
      reporter = reporter,
      reportCachedProblems = reportCachedProblems
    )
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
        val workerDir = init.taskDest / "zinc-worker" / key.hashCode.toString
        val daemonDir = workerDir / "daemon"

        os.makeDir.all(daemonDir)
        os.write.over(workerDir / "java-home", key.javaHome.map(_.toString).getOrElse("<default>"))
        os.write.over(workerDir / "java-runtime-options", key.runtimeOptions.mkString("\n"))

        val mainClass = "mill.javalib.worker.MillJvmWorkerMain"
        val baseLocks = Locks.forDirectory(daemonDir.toString, useFileLocks)
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
              mainArgs = Seq(daemonDir.toString, jobs.toString, useFileLocks.toString),
              javaHome = key.javaHome,
              jvmArgs = key.runtimeOptions ++ suppressArgs,
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

private[mill] object JvmWorkerImpl {

  /** Prepends `-release <major>` to scalac options unless the user already set one. */
  private def withScalaRelease(op: ZincOp, release: Int): ZincOp = op match {
    case m: ZincOp.CompileMixed =>
      m.copy(scalacOptions = injectRelease(m.scalacOptions, release))
    case s: ZincOp.ScaladocJar =>
      s.copy(args = injectRelease(s.args, release))
    case other => other
  }

  private def injectRelease(opts: Seq[String], release: Int): Seq[String] = {
    val alreadySet = opts.exists(o =>
      o == "-release" || o == "--release" ||
        o.startsWith("-release:") || o.startsWith("--release=") ||
        o.startsWith("-Xrelease") || o.startsWith("-target:") || o == "-target"
    )
    if (alreadySet) opts else Seq("-release", release.toString) ++ opts
  }
}
