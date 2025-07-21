package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.javalib.api.{CompilationResult, JvmWorkerApi}
import mill.javalib.internal.{JvmWorkerArgs, RpcCompileProblemReporterMessage}
import mill.javalib.zinc.ZincWorkerRpcServer.ReporterMode
import mill.javalib.zinc.{ZincWorker, ZincWorkerApi, ZincWorkerRpcServer}
import mill.rpc.RpcConsole.Message
import mill.rpc.{MillRpcChannel, MillRpcClient, MillRpcRequestId, MillRpcWireTransport}
import mill.util.Jvm
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

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    runWith(javaHome, javacOptions = Seq.empty) { (zinc, _) =>
      zinc.docJar(
        scalaVersion = scalaVersion,
        scalaOrganization = scalaOrganization,
        compilerClasspath = compilerClasspath,
        scalacPluginClasspath = scalacPluginClasspath,
        args = args
      )
    }
  }

  override def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    runWith(javaHome, javacOptions) { (zinc, javacOptions) =>
      zinc.compileJava(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
        incrementalCompilation = incrementalCompilation
      )
    }
  }

  override def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String]
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    runWith(javaHome, javacOptions) { (zinc, javacOptions) =>
      zinc.compileMixed(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalaVersion = scalaVersion,
        scalaOrganization = scalaOrganization,
        scalacOptions = scalacOptions,
        compilerClasspath = compilerClasspath,
        scalacPluginClasspath = scalacPluginClasspath,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
        incrementalCompilation = incrementalCompilation,
        auxiliaryClassFileExtensions = auxiliaryClassFileExtensions
      )
    }
  }

  override def close(): Unit = {
    close0()
    zincLocalWorker.close()
    // TODO review: close the subprocesses
  }

  private def runWith[A](
      javaHome: Option[os.Path],
      javacOptions: Seq[String]
  )(f: (ZincWorkerApi, JavaCompilerOptions) => A)(using
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

    if (jOpts.runtime.options.isEmpty && javaHome.isEmpty) {
      val zincDeps =
        ZincWorker.InvocationDependencies(
          log = log,
          consoleOut = ConsoleOut.printStreamOut(log.streams.err)
        )

      val api = new ZincWorkerApi {
        override def compileJava(
            upstreamCompileOutput: Seq[CompilationResult],
            sources: Seq[Path],
            compileClasspath: Seq[Path],
            javacOptions: JavaCompilerOptions,
            reporter: Option[CompileProblemReporter],
            reportCachedProblems: Boolean,
            incrementalCompilation: Boolean
        ): Result[CompilationResult] = zincLocalWorker.compileJava(
          upstreamCompileOutput = upstreamCompileOutput,
          sources = sources,
          compileClasspath = compileClasspath,
          javacOptions = javacOptions,
          reporter = reporter,
          reportCachedProblems = reportCachedProblems,
          incrementalCompilation = incrementalCompilation
        )(using zincCtx, zincDeps)

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
        ): Result[CompilationResult] = zincLocalWorker.compileMixed(
          upstreamCompileOutput = upstreamCompileOutput,
          sources = sources,
          compileClasspath = compileClasspath,
          javacOptions = javacOptions,
          scalaVersion = scalaVersion,
          scalaOrganization = scalaOrganization,
          scalacOptions = scalacOptions,
          compilerClasspath = compilerClasspath,
          scalacPluginClasspath = scalacPluginClasspath,
          reporter = reporter,
          reportCachedProblems = reportCachedProblems,
          incrementalCompilation = incrementalCompilation,
          auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
          compilerBridgeData = ()
        )(using zincCtx, zincDeps)

        override def docJar(
            scalaVersion: String,
            scalaOrganization: String,
            compilerClasspath: Seq[PathRef],
            scalacPluginClasspath: Seq[PathRef],
            args: Seq[String]
        ): Boolean = zincLocalWorker.docJar(
          scalaVersion = scalaVersion,
          scalaOrganization = scalaOrganization,
          compilerClasspath = compilerClasspath,
          scalacPluginClasspath = scalacPluginClasspath,
          args = args,
          compilerBridgeData = ()
        )
      }

      f(api, jOpts.compiler)
    } else runWithSpawned(javaHome, jOpts.runtime, zincCtx, log) { worker =>
      f(worker, jOpts.compiler)
    }
  }

  /** Spawns a [[ZincWorkerApi]] subprocess with the specified java version and runtime options. */
  private def runWithSpawned[A](
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions,
      ctx: ZincWorker.InvocationContext,
      log: Logger
  )(f: ZincWorkerApi => A): A = {
    def debugStr = s"javaHome=$javaHome, runtimeOptions=$runtimeOptions"

    def makeRpcClient(handler: MillRpcChannel[ZincWorkerRpcServer.ServerToClient])
        : MillRpcClient[ZincWorkerRpcServer.ClientToServer] = {
      log.info(s"Spawning ZincWorkerMain with $debugStr")
      val process = Jvm.spawnProcess(
        mainClass = "mill.javalib.zinc.ZincWorkerMain",
        javaHome = javaHome,
        jvmArgs = runtimeOptions.options,
        classPath = classPath
      )
      log.info(s"ZincWorkerMain JVM process spawned with PID ${process.wrapped.pid}.")
      val wireTransport = MillRpcWireTransport.ViaStdinAndStdoutOfSubprocess(process)
      val initialize = ZincWorkerRpcServer.Initialize(
        taskDest = ctx.dest,
        jobs = jobs,
        compileToJar = compileToJar,
        zincLogDebug = zincLogDebug
      )
      MillRpcClient.create[
        ZincWorkerRpcServer.Initialize,
        ZincWorkerRpcServer.ClientToServer,
        ZincWorkerRpcServer.ServerToClient
      ](initialize, wireTransport, log)(handler)
    }

    def toReportingMode(
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

    def serverRpcToClientHandler(reporter: Option[CompileProblemReporter])
        : MillRpcChannel[ZincWorkerRpcServer.ServerToClient] = new {
      override def apply(
          requestId: MillRpcRequestId,
          input: ZincWorkerRpcServer.ServerToClient
      ): input.Response = input match {
        case msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge =>
          acquireZincCompilerBridge(msg).asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem =>
          reportCompilationProblem(msg).asInstanceOf[input.Response]
        case msg: ZincWorkerRpcServer.ServerToClient.Console =>
          console(msg).asInstanceOf[input.Response]
      }

      private def acquireZincCompilerBridge(
          msg: ZincWorkerRpcServer.ServerToClient.AcquireZincCompilerBridge
      ): msg.Response =
        compilerBridge.acquire(msg.scalaVersion, msg.scalaOrganization, data = ())

      private def reportCompilationProblem(
          msg: ZincWorkerRpcServer.ServerToClient.ReportCompilationProblem
      ): msg.Response =
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
              s"Received compilation problem from JVM worker ($debugStr), but no reporter was provided, " +
                s"this is a bug in Mill. Ignoring the compilation problem for now.\n\n" +
                s"Problem: ${pprint.apply(msg)}"
            )
        }

      private def console(msg: ZincWorkerRpcServer.ServerToClient.Console): msg.Response = {
        val out = if (msg.stderr) Console.err else Console.out
        msg.msg match {
          case Message.Print(s) => out.print(s"[RPC-SERVER] $s")
          case Message.Flush => out.flush()
        }
      }
    }

    val api = new ZincWorkerApi {
      override def compileJava(
          upstreamCompileOutput: Seq[CompilationResult],
          sources: Seq[Path],
          compileClasspath: Seq[Path],
          javacOptions: JavaCompilerOptions,
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean,
          incrementalCompilation: Boolean
      ): Result[CompilationResult] = {
        val rpcClient = makeRpcClient(serverRpcToClientHandler(reporter))

        val msg = ZincWorkerRpcServer.ClientToServer.CompileJava(
          upstreamCompileOutput = upstreamCompileOutput,
          sources = sources,
          compileClasspath = compileClasspath,
          javacOptions = javacOptions,
          reporterMode = toReportingMode(reporter, reportCachedProblems),
          incrementalCompilation = incrementalCompilation,
          ctx = ctx
        )
        Result.fromEither(rpcClient(msg))
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
        val rpcClient = makeRpcClient(serverRpcToClientHandler(reporter))

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
          reporterMode = toReportingMode(reporter, reportCachedProblems),
          incrementalCompilation = incrementalCompilation,
          auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
          ctx = ctx
        )
        Result.fromEither(rpcClient(msg))
      }

      override def docJar(
          scalaVersion: String,
          scalaOrganization: String,
          compilerClasspath: Seq[PathRef],
          scalacPluginClasspath: Seq[PathRef],
          args: Seq[String]
      ): Boolean = {
//        zincLocalWorker.docJar(
//          scalaVersion = scalaVersion,
//          scalaOrganization = scalaOrganization,
//          compilerClasspath = compilerClasspath,
//          scalacPluginClasspath = scalacPluginClasspath,
//          args = args
//        )(using zincCtx)
        ???
      }
    }

    f(api)
  }
}
