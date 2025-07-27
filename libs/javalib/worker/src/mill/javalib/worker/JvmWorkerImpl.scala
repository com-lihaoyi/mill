package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.javalib.api.internal.{ZincCompileJava, ZincCompileMixed, ZincScaladocJar}
import mill.javalib.api.{CompilationResult, JvmWorkerApi, ZincApi}
import mill.javalib.internal.{JvmWorkerArgs, RpcCompileProblemReporterMessage}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincCompileJava, ZincCompileMixed, ZincScaladocJar, ZincWorker, ZincWorkerRpcServer}
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
    reporter: Option[CompileProblemReporter],
    reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    runWith(javaHome, javacOptions) { (zinc, javacOptions) =>
      zinc.compileJava(
        op,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
      )
    }
  }

  override def compileMixed(
    op: ZincCompileMixed,
    reporter: Option[CompileProblemReporter],
    reportCachedProblems: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    runWith(javaHome, javacOptions) { (zinc, javacOptions) =>
      zinc.compileMixed(
        op,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
      )
    }
  }

  def docJar(
    op: ZincScaladocJar,
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    runWith(javaHome, javacOptions = Seq.empty) { (zinc, _) =>
      zinc.scaladocJar(op)
    }
  }

  override def close(): Unit = {
    close0()
    zincLocalWorker.close()
    subprocessCache.close()
  }

  /**
   * Runs the given function using either the local Zinc instance or the remote Zinc instance depending on the java
   * home and javac options.
   */
  private def runWith[A](
      javaHome: Option[os.Path],
      javacOptions: Seq[String]
  )(f: (ZincApi, JavaCompilerOptions) => A)(using
      ctx: JvmWorkerApi.Ctx
  ): A = {
    val jOpts = JavaCompilerOptions(javacOptions)
    val log = ctx.log
    val zincCtx = ZincWorker.InvocationContext(
      env = ctx.env,
      dest = ctx.dest,
      logDebugEnabled = log.debugEnabled,
      logPromptColored = log.prompt.colored
    )

    if (jOpts.runtime.options.isEmpty && javaHome.isEmpty) runWithLocalZinc()
    else runWithSpawned(javaHome, jOpts.runtime, zincCtx, log) { worker =>
      f(worker, jOpts.compiler)
    }
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
    ctx: ZincWorker.InvocationContext,
    log: Logger
  ): ZincApi = {
    val zincDeps =
      ZincWorker.InvocationDependencies(
        log = log,
        consoleOut = ConsoleOut.printStreamOut(log.streams.err)
      )

    new {
      override def compileJava(
        op: ZincCompileJava,
        reporter: Option[CompileProblemReporter],
        reportCachedProblems: Boolean,
      ): Result[CompilationResult] = zincLocalWorker.compileJava(
        op = op,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
      )(using ctx, zincDeps)

      override def compileMixed(
        op: ZincCompileMixed,
        reporter: Option[CompileProblemReporter],
        reportCachedProblems: Boolean,
      ): Result[CompilationResult] = zincLocalWorker.compileMixed(
        op = op,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
        compilerBridgeData = ()
      )(using ctx, zincDeps)

      override def scaladocJar(op: ZincScaladocJar): Boolean =
        zincLocalWorker.scaladocJar(op, compilerBridgeData = ())
    }
  }

  /**
   * Spawns a [[ZincApi]] subprocess with the specified java version and runtime options and runs the given
   * function with it.
   */
  private def runWithSpawned[A](
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions,
      ctx: ZincWorker.InvocationContext,
      log: Logger
  )(f: ZincApi => A): A = {
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

    val api = new ZincApi {
      override def compileJava(
          upstreamCompileOutput: Seq[CompilationResult],
          sources: Seq[Path],
          compileClasspath: Seq[Path],
          javacOptions: JavaCompilerOptions,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean,
          incrementalCompilation: Boolean
      ): Result[CompilationResult] = {
        withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.CompileJava(
            upstreamCompileOutput = upstreamCompileOutput,
            sources = sources,
            compileClasspath = compileClasspath,
            javacOptions = javacOptions,
            reporterMode = toReporterMode(reporter, reportCachedProblems),
            incrementalCompilation = incrementalCompilation,
            ctx = ctx
          )
          rpcClient(msg)
        }
      }

      override def compileMixed(
          upstreamCompileOutput: Seq[CompilationResult],
          sources: Seq[Path],
          compileClasspath: Seq[Path],
          javacOptions: JavaCompilerOptions,
          scalaVersion: String,
          scalaOrganization: String,
          scalacOptions: Seq[String],
          compilerClasspath: Seq[PathRef],
          scalacPluginClasspath: Seq[PathRef],
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean,
          incrementalCompilation: Boolean,
          auxiliaryClassFileExtensions: Seq[String]
      ): Result[CompilationResult] = {
        withRpcClient(serverRpcToClientHandler(reporter, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.CompileMixed(
            upstreamCompileOutput = upstreamCompileOutput,
            sources = sources,
            compileClasspath = compileClasspath,
            javacOptions = javacOptions,
            scalaVersion = scalaVersion,
            scalaOrganization = scalaOrganization,
            scalacOptions = scalacOptions,
            compilerClasspath = compilerClasspath,
            scalacPluginClasspath = scalacPluginClasspath,
            reporterMode = toReporterMode(reporter, reportCachedProblems),
            incrementalCompilation = incrementalCompilation,
            auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
            ctx = ctx
          )
          rpcClient(msg)
        }
      }

      override def scaladocJar(
          scalaVersion: String,
          scalaOrganization: String,
          compilerClasspath: Seq[PathRef],
          scalacPluginClasspath: Seq[PathRef],
          args: Seq[String]
      ): Boolean = {
        withRpcClient(serverRpcToClientHandler(reporter = None, log, cacheKey)) { rpcClient =>
          val msg = ZincWorkerRpcServer.ClientToServer.DocJar(
            scalaVersion = scalaVersion,
            scalaOrganization = scalaOrganization,
            compilerClasspath = compilerClasspath,
            scalacPluginClasspath = scalacPluginClasspath,
            args = args
          )
          rpcClient(msg)
        }
      }
    }

    f(api)
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
