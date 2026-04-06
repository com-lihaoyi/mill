package mill.javalib.api

import mill.api.PathRef
import mill.api.daemon.Result
import mill.api.daemon.internal.CompileProblemReporter

import scala.annotation.unused

object JvmWorkerApi {
  type Ctx = mill.api.TaskCtx.Dest & mill.api.TaskCtx.Log & mill.api.TaskCtx.Env
}
trait JvmWorkerApi {

  /** Compile a Java-only project */
  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      @unused workDir: os.Path
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] =
    // default-impl for bin-compat
    compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javaHome = javaHome,
      javacOptions = javacOptions,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems,
      incrementalCompilation = incrementalCompilation
    )

  /** Compile a mixed Scala/Java or Scala-only project */
  def compileMixed(
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
      @unused compilerBridgeOpt: Option[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String],
      @unused workDir: os.Path
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] =
    // default-impl for bin-compat
    compileMixed(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javaHome = javaHome,
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

  /** Compiles a Scaladoc jar. */
  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      @unused compilerBridgeOpt: Option[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String],
      @unused workDir: os.Path
  )(using ctx: JvmWorkerApi.Ctx): Boolean =
    // default-impl for bin-compat
    docJar(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      javaHome = javaHome,
      args = args
    )

  // Bin compat shims below

  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] =
    compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javaHome = javaHome,
      javacOptions = javacOptions,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems,
      incrementalCompilation = incrementalCompilation,
      workDir = ctx.dest
    )

  def compileMixed(
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
  )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] =
    compileMixed(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javaHome = javaHome,
      javacOptions = javacOptions,
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      scalacOptions = scalacOptions,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      compilerBridgeOpt = None,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems,
      incrementalCompilation = incrementalCompilation,
      auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
      workDir = ctx.dest
    )

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean =
    docJar(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      compilerBridgeOpt = None,
      javaHome = javaHome,
      args = args,
      workDir = ctx.dest
    )
}
