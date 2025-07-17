package mill.javalib.worker

import mill.api.*
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.javalib.api.{CompilationResult, JvmWorkerApi}
import mill.javalib.internal.JvmWorkerArgs
import mill.javalib.zinc.ZincWorker

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends JvmWorkerApi with AutoCloseable {
  import args.*

  private val zincLocalWorker =
    ZincWorker(compilerBridge, jobs = jobs, compileToJar = compileToJar, zincLogDebug = zincLogDebug)

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    zincLocalWorker.docJar(
      scalaVersion = scalaVersion, scalaOrganization = scalaOrganization, compilerClasspath = compilerClasspath, scalacPluginClasspath = scalacPluginClasspath, args = args
    )
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
    val jOpts = JavaCompilerOptions(javacOptions)
    zincLocalWorker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput, sources = sources, compileClasspath = compileClasspath, javacOptions = jOpts.compiler, reporter = reporter,
      reportCachedProblems = reportCachedProblems, incrementalCompilation = incrementalCompilation
    )
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
    val jOpts = JavaCompilerOptions(javacOptions)
    zincLocalWorker.compileMixed(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javacOptions = jOpts.compiler,
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      scalacOptions = scalacOptions,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems,
      incrementalCompilation = incrementalCompilation,
      auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
    )
  }

  override def close(): Unit = {
    close0()
    zincLocalWorker.close()
    // TODO review: close the subprocesses
  }
}
