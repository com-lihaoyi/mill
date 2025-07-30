package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.client.ServerLauncher
import mill.client.lock.Locks
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.*
import mill.javalib.internal.{JvmWorkerArgs, RpcCompileProblemReporterMessage}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincApi, ZincWorker, ZincWorkerRpcServer}
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcWireTransport}
import mill.util.{CachedFactoryWithInitData, Jvm}
import sbt.internal.util.ConsoleOut

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.security.MessageDigest
import java.util.HexFormat
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*
import scala.util.Using

@internal
class JvmWorkerImpl(args: JvmWorkerArgs[Unit]) extends JvmWorkerApi with AutoCloseable {
  import args.*

  /** The local Zinc instance which is used when we do not want to override Java home or runtime options. */
  private val zincLocalWorker =
    ZincWorker(
      compilerBridge,
      jobs = jobs,
      compileToJar = compileToJar,
      zincLogDebug = zincLogDebug
    )

  override def compileJava(
      op: ZincCompileJava,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    val zinc = zincApi(javaHome, javaRuntimeOptions)
    zinc.compileJava(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  override def compileMixed(
      op: ZincCompileMixed,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    val zinc = zincApi(javaHome, javaRuntimeOptions)
    zinc.compileMixed(op, reporter = reporter, reportCachedProblems = reportCachedProblems)
  }

  def scaladocJar(
      op: ZincScaladocJar,
      javaHome: Option[os.Path]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    val zinc = zincApi(javaHome, JavaRuntimeOptions(Seq.empty))
    zinc.scaladocJar(op)
  }

  override def close(): Unit = {
    close0()
    zincLocalWorker.close()
    subprocessCache.close()
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
      logPromptColored = log.prompt.colored
    )

    if (javaRuntimeOptions.options.isEmpty && javaHome.isEmpty) localZincApi(zincCtx, log)
    else subprocessZincApi(javaHome, javaRuntimeOptions, zincCtx, log)
  }

  private case class SubprocessCacheKey(
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions
  ) {
    def debugStr = s"javaHome=$javaHome, runtimeOptions=$runtimeOptions"

    def sha256: String = {
      val digest = MessageDigest.getInstance("sha256").digest(debugStr.getBytes("UTF-8"))
      HexFormat.of().formatHex(digest)
    }
  }
  private case class SubprocessCacheInitialize(
      taskDest: os.Path,
//      zincRpcServerInit: ZincWorkerRpcServer.Initialize,
//      handler: MillRpcChannel[ZincWorkerRpcServer.ServerToClient]
  )
  private case class SubprocessCacheValue(port: Int)
  private val subprocessCache = new CachedFactoryWithInitData[
    SubprocessCacheKey,
    SubprocessCacheInitialize,
    SubprocessCacheValue
  ] {
    override def maxCacheSize: Int = jobs

    override def setup(
        key: SubprocessCacheKey,
        init: SubprocessCacheInitialize
    ): SubprocessCacheValue = {
      val workerDir = init.taskDest / "zinc-worker" / key.sha256
      val daemonDir = workerDir / "daemon"
      os.makeDir.all(daemonDir)
      os.write.over(workerDir / "java-home", key.javaHome.map(_.toString).getOrElse("<default>"))
      os.write.over(workerDir / "java-runtime-options", key.runtimeOptions.options.mkString("\n"))
      val locks = Locks.files(daemonDir.toString)
      val mainClass = "mill.javalib.zinc.ZincWorkerMain"

      ServerLauncher.ensureServerIsRunning(
        locks,
        daemonDir.toNIO,
        () =>
          Jvm.spawnProcess(
            mainClass = mainClass,
            mainArgs = Seq(daemonDir.toString),
            javaHome = key.javaHome,
            jvmArgs = key.runtimeOptions.options,
            classPath = classPath,
            stdout = os.Inherit
          ).wrapped
      ).toScala.foreach { failure =>
        throw Exception(
          s"""Failed to launch '$mainClass' for:
             |  javaHome = ${key.javaHome}
             |  runtimeOptions = ${key.runtimeOptions.options.mkString(",")}
             |  daemonDir = $daemonDir
             |
             |Failure:
             |${failure.debugString}
             |""".stripMargin
        )
      }

      val serverInitWaitMillis = 5.seconds.toMillis.toInt
      val startTime = System.currentTimeMillis
      val port = ServerLauncher.readServerPort(daemonDir.toNIO, startTime, serverInitWaitMillis)
      SubprocessCacheValue(port)
    }

    override def teardown(key: SubprocessCacheKey, value: SubprocessCacheValue): Unit = {
      // TODO review: should we kill the process?
    }
  }

  /** Gives you API for the [[zincLocalWorker]] instance. */
  private def localZincApi(
      zincCtx: ZincWorker.InvocationContext,
      log: Logger
  ): ZincApi = {
    val zincDeps = ZincWorker.InvocationDependencies(
      log = log,
      consoleOut = ConsoleOut.printStreamOut(log.streams.err)
    )

    zincLocalWorker.api(compilerBridgeData = ())(using zincCtx, zincDeps)
  }

  /**
   * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
   * instance for it.
   */
  private def subprocessZincApi(
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions,
      ctx: ZincWorker.InvocationContext,
      log: Logger
  ): ZincApi = {
    val cacheKey = SubprocessCacheKey(javaHome, runtimeOptions)

    def withRpcClient[R](
        handler: MillRpcChannel[ZincWorkerRpcServer.ServerToClient]
    )(f: MillRpcClient[ZincWorkerRpcServer.ClientToServer, ZincWorkerRpcServer.ServerToClient] => R)
        : R = {
      subprocessCache.withValue(
        cacheKey,
        SubprocessCacheInitialize(ctx.dest)
      ) { case SubprocessCacheValue(port) =>
        Using.Manager { use =>
          val startTimeMillis = System.currentTimeMillis()
          val socket = use(ServerLauncher.connectToServer(startTimeMillis, 5.seconds.toMillis.toInt, port))
          val stdin = use(BufferedReader(InputStreamReader(socket.getInputStream)))
          val stdout = use(PrintStream(socket.getOutputStream))
          val wireTransport = MillRpcWireTransport.ViaStreams(
            s"TCP ${socket.getRemoteSocketAddress} -> ${socket.getLocalSocketAddress}", stdin, stdout
          )

          val init = ZincWorkerRpcServer.Initialize(
            compilerBridgeWorkspace = compilerBridge.workspace,
            jobs = jobs,
            compileToJar = compileToJar,
            zincLogDebug = zincLogDebug
          )
          val client = MillRpcClient.create[
            ZincWorkerRpcServer.Initialize,
            ZincWorkerRpcServer.ClientToServer,
            ZincWorkerRpcServer.ServerToClient
          ](init, wireTransport, log)(handler)

          f(client)
        }.get
      }
    }

    new {
      override def compileJava(
          op: ZincCompileJava,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean
      ): Result[CompilationResult] = {
        withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.CompileJava(
            op,
            reporterMode = toReporterMode(reporter, reportCachedProblems),
            ctx = ctx
          )
          rpcClient(msg)
        }
      }

      override def compileMixed(
          op: ZincCompileMixed,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean
      ): Result[CompilationResult] = {
        withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.CompileMixed(
            op,
            reporterMode = toReporterMode(reporter, reportCachedProblems),
            ctx = ctx
          )
          rpcClient(msg)
        }
      }

      override def scaladocJar(op: ZincScaladocJar): Boolean = {
        withRpcClient(serverRpcToClientHandler(reporter = None, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.ScaladocJar(op)
          rpcClient(msg)
        }
      }
    }
  }

  /** Handles messages sent from the Zinc RPC server. */
  private def serverRpcToClientHandler(
      reporter: Option[CompileProblemReporter],
      log: Logger,
      cacheKey: SubprocessCacheKey
  )
      : MillRpcChannel[ZincWorkerRpcServer.ServerToClient] = {
    def acquireZincCompilerBridge(
        msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge
    ): msg.Response =
      compilerBridge.acquire(msg.scalaVersion, msg.scalaOrganization, data = ())

    def reportCompilationProblem(
        msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem
    ): msg.Response = {
      reporter match {
        case Some(reporter) => msg.problem match {
            case RpcCompileProblemReporterMessage.Start => reporter.start()
            case RpcCompileProblemReporterMessage.LogError(problem) => reporter.logError(problem)
            case RpcCompileProblemReporterMessage.LogWarning(problem) =>
              reporter.logWarning(problem)
            case RpcCompileProblemReporterMessage.LogInfo(problem) => reporter.logInfo(problem)
            case RpcCompileProblemReporterMessage.FileVisited(file) =>
              reporter.fileVisited(file.toNIO)
            case RpcCompileProblemReporterMessage.PrintSummary => reporter.printSummary()
            case RpcCompileProblemReporterMessage.Finish => reporter.finish()
            case RpcCompileProblemReporterMessage.NotifyProgress(percentage, total) =>
              reporter.notifyProgress(percentage = percentage, total = total)
          }

        case None =>
          log.warn(
            s"Received compilation problem from JVM worker (${cacheKey.debugStr}), but no reporter was provided, " +
              s"this is a bug in Mill. Ignoring the compilation problem for now.\n\n" +
              s"Problem: ${pprint.apply(msg)}"
          )
      }
    }

    (_, input) => {
      input match {
        case msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge =>
          acquireZincCompilerBridge(msg).asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem =>
          reportCompilationProblem(msg).asInstanceOf[input.Response]
      }
    }
  }

  private def toReporterMode(
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): ReporterMode = reporter match {
    case None => ReporterMode.NoReporter
    case Some(reporter) =>
      ReporterMode.Reporter(
        reportCachedProblems = reportCachedProblems,
        maxErrors = reporter.maxErrors
      )
  }
}
