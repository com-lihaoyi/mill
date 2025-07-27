package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.javalib.api.internal.{
  JavaCompilerOptions,
  JavaRuntimeOptions,
  ZincCompileJava,
  ZincCompileMixed,
  ZincScaladocJar
}
import mill.javalib.api.{CompilationResult, JvmWorkerApi}
import mill.javalib.internal.{JvmWorkerArgs, RpcCompileProblemReporterMessage}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincApi, ZincWorker, ZincWorkerRpcServer}
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcWireTransport}
import mill.util.{CachedFactoryWithInitData, Jvm}
import os.Path
import sbt.internal.util.ConsoleOut

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
  }
  private case class SubprocessCacheInitialize(
      zincRpcServerInit: ZincWorkerRpcServer.Initialize,
      log: Logger.Actions,
      handler: MillRpcChannel[ZincWorkerRpcServer.ServerToClient]
  )
  private val subprocessCache = new CachedFactoryWithInitData[
    SubprocessCacheKey,
    SubprocessCacheInitialize,
    MillRpcClient[ZincWorkerRpcServer.ClientToServer, ZincWorkerRpcServer.ServerToClient]
  ] {
    override def maxCacheSize: Int = jobs

    override def setup(
        key: SubprocessCacheKey,
        init: SubprocessCacheInitialize
    ): MillRpcClient[ZincWorkerRpcServer.ClientToServer, ZincWorkerRpcServer.ServerToClient] = {
      println(s"Spawning JVM worker subprocess: ${key.debugStr}")
      val process = Jvm.spawnProcess(
        mainClass = "mill.javalib.zinc.ZincWorkerMain",
        javaHome = key.javaHome,
        jvmArgs = key.runtimeOptions.options,
        classPath = classPath
      )
      val wireTransport = MillRpcWireTransport.ViaStdinAndStdoutOfSubprocess(process)

      MillRpcClient.create[
        ZincWorkerRpcServer.Initialize,
        ZincWorkerRpcServer.ClientToServer,
        ZincWorkerRpcServer.ServerToClient
      ](init.zincRpcServerInit, wireTransport, init.log)(init.handler)
    }

    override def teardown(
        key: SubprocessCacheKey,
        client: MillRpcClient[
          ZincWorkerRpcServer.ClientToServer,
          ZincWorkerRpcServer.ServerToClient
        ]
    ): Unit = {
      println(s"Tearing down JVM worker subprocess: ${key.debugStr}")
      client.close()
    }
  }

  /** Gives you API for the [[zincLocalWorker]] instance. */
  private def localZincApi(
      zincCtx: ZincWorker.InvocationContext,
      log: Logger
  ): ZincApi = {
    val zincDeps =
      ZincWorker.InvocationDependencies(
        log = log,
        consoleOut = ConsoleOut.printStreamOut(log.streams.err)
      )

    zincLocalWorker.api(compilerBridgeData = ())(using zincCtx, zincDeps)
  }

  /**
   * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and returns a [[ZincApi]]
   * instance for it.
   */
  private def subprocessZincApi[A](
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
        SubprocessCacheInitialize(
          ZincWorkerRpcServer.Initialize(
            taskDest = ctx.dest, // TODO review: suspicious
            jobs = jobs,
            compileToJar = compileToJar,
            zincLogDebug = zincLogDebug
          ),
          log,
          (requestId, msg) =>
            throw new IllegalStateException(
              s"Server message handler is not ready to handle request $requestId: $msg"
            )
        )
      ) { client =>
        // Exchange the handler from the cached value.
        client.withServerToClientHandler(handler)

        f(client)
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
