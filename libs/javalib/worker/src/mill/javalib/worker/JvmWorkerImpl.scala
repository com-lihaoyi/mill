package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.javalib.api.{CompilationResult, JvmWorkerApi}
import mill.javalib.internal.{JvmWorkerArgs, ZincCompilerBridge}
import mill.javalib.zinc.ZincWorkerMain.ReporterMode
import mill.javalib.zinc.{ZincWorker, ZincWorkerApi, ZincWorkerMain}
import mill.rpc.MillRpcChannel
import mill.util.Jvm
import os.Path

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends JvmWorkerApi with AutoCloseable {
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
    val zincCtx = ZincWorker.InvocationContext(
      env = ctx.env,
      dest = ctx.dest,
      logDebugEnabled = ctx.log.debugEnabled,
      logPromptColored = ctx.log.prompt.colored
    )

    if (jOpts.runtime.options.isEmpty && javaHome.isEmpty) {
      val zincDeps =
        ZincWorker.InvocationDependencies(log = ctx.log, errorStream = ctx.log.streams.err)

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
          auxiliaryClassFileExtensions = auxiliaryClassFileExtensions
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
          args = args
        )(using zincCtx)
      }

      f(api, jOpts.compiler)
    } else runWithSpawned(javaHome, jOpts.runtime, zincCtx) { worker =>
      f(worker, jOpts.compiler)
    }
  }

  /** Spawns a [[ZincWorkerApi]] subprocess with the specified java version and runtime options. */
  private def runWithSpawned[A](
      javaHome: Option[os.Path],
      runtimeOptions: JavaRuntimeOptions,
      ctx: ZincWorker.InvocationContext
  )(f: ZincWorkerApi => A): A = {
    trait ZincServerToClientHandler {
      def handle(msg: ZincWorkerMain.ServerToClient): msg.Response
    }

    def getChannel(handler: ZincServerToClientHandler): MillRpcChannel[ZincWorkerMain.ClientToServer] = {
      val process = Jvm.spawnProcess("mill.javalib.zinc.ZincWorkerMain", javaHome = javaHome, jvmArgs = runtimeOptions.options)
      process.stdin
      ???
    }

    def toReportingMode(
        reporter: Option[CompileProblemReporter],
        reportCachedProblems: Boolean
    ): ReporterMode = reporter match {
      case None => ReporterMode.NoReporter
      // TODO review: forward data sent from the RPC to the reporter
      case Some(_) => ReporterMode.Reporter(reportCachedProblems = reportCachedProblems)
    }

    val serverRpcToClientHandler: ZincServerToClientHandler = new {
      override def handle(msg: ZincWorkerMain.ServerToClient): msg.Response = msg match {
        case msg: ZincWorkerMain.ServerToClient.InvokeZincCompilerBridgeCompile =>
          invokeZincCompilerBridgeCompile(msg).asInstanceOf
        case msg: ZincWorkerMain.ServerToClient.ReportCompilationProblem =>
          reportCompilationProblem(msg).asInstanceOf
      }

      private def invokeZincCompilerBridgeCompile(
          msg: ZincWorkerMain.ServerToClient.InvokeZincCompilerBridgeCompile
      ): msg.Response =
        compilerBridge match {
          case ZincCompilerBridge.Compiled(forScalaVersion) => ??? // TODO review
          case provider: ZincCompilerBridge.Provider =>
            provider.compile(msg.scalaVersion, msg.scalaOrganization)
        }

      private def reportCompilationProblem(msg: ZincWorkerMain.ServerToClient.ReportCompilationProblem): msg.Response =
        ???
    }

    val channel = getChannel(serverRpcToClientHandler)

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
        val msg = ZincWorkerMain.ClientToServer.CompileJava(
          upstreamCompileOutput = upstreamCompileOutput,
          sources = sources,
          compileClasspath = compileClasspath,
          javacOptions = javacOptions,
          reporterMode = toReportingMode(reporter, reportCachedProblems),
          incrementalCompilation = incrementalCompilation,
          ctx = ctx
        )
        val either = channel(msg)
        Result.fromEither(either)
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
//        zincLocalWorker.compileMixed(
//          upstreamCompileOutput = upstreamCompileOutput,
//          sources = sources,
//          compileClasspath = compileClasspath,
//          javacOptions = javacOptions,
//          scalaVersion = scalaVersion,
//          scalaOrganization = scalaOrganization,
//          scalacOptions = scalacOptions,
//          compilerClasspath = compilerClasspath,
//          scalacPluginClasspath = scalacPluginClasspath,
//          reporter = reporter,
//          reportCachedProblems = reportCachedProblems,
//          incrementalCompilation = incrementalCompilation,
//          auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
//        )(using zincCtx, zincDeps)
        ???
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
