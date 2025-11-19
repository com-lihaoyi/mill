package mill.javalib.api.internal

import mill.api.{PathRef, Result}
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.api.JvmWorkerApi as PublicJvmWorkerApi
import mill.javalib.api.JvmWorkerApi.Ctx
import mill.javalib.api.internal.ZincOp
import os.Path

trait InternalJvmWorkerApi extends PublicJvmWorkerApi {

  /** Compile a Java-only project. */
  def apply(
      op: ZincOp,
      javaHome: Option[os.Path],
      javaRuntimeOptions: Seq[String] = Nil,
      reporter: Option[CompileProblemReporter] = None,
      reportCachedProblems: Boolean = false
  )(using context: InternalJvmWorkerApi.Ctx): op.Response

  // public API forwarder
  override def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[Path],
      compileClasspath: Seq[Path],
      javaHome: Option[Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      workDir: os.Path
  )(using ctx: Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions.split(javacOptions)
    apply(
      ZincOp.CompileJava(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = jOpts.compiler,
        incrementalCompilation = incrementalCompilation,
        workDir = workDir
      ),
      javaHome = javaHome,
      javaRuntimeOptions = jOpts.runtime,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems
    )
  }

  // public API forwarder
  override def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[Path],
      compileClasspath: Seq[Path],
      javaHome: Option[Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String],
      workDir: os.Path
  )(using ctx: Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions.split(javacOptions)
    apply(
      ZincOp.CompileMixed(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = jOpts.compiler,
        scalaVersion = scalaVersion,
        scalaOrganization = scalaOrganization,
        scalacOptions = scalacOptions,
        compilerClasspath = compilerClasspath,
        scalacPluginClasspath = scalacPluginClasspath,
        incrementalCompilation = incrementalCompilation,
        auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
        workDir = workDir
      ),
      javaHome = javaHome,
      javaRuntimeOptions = jOpts.runtime,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems
    )
  }

  // public API forwarder
  override def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[Path],
      args: Seq[String],
      workDir: os.Path
  )(using ctx: Ctx): Boolean = {
    apply(
      op = ZincOp.ScaladocJar(
        scalaVersion = scalaVersion,
        scalaOrganization = scalaOrganization,
        compilerClasspath = compilerClasspath,
        scalacPluginClasspath = scalacPluginClasspath,
        args = args,
        workDir = workDir
      ),
      javaHome = javaHome,
      javaRuntimeOptions = Nil,
      reporter = None,
      reportCachedProblems = false
    )
  }
}
object InternalJvmWorkerApi {
  type Ctx = PublicJvmWorkerApi.Ctx
}
