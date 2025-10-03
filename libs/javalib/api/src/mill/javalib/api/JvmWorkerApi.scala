package mill.javalib.api

import mill.api.PathRef
import mill.api.daemon.internal.CompileProblemReporter

object JvmWorkerApi {
  type Ctx = mill.api.TaskCtx.Dest & mill.api.TaskCtx.Log & mill.api.TaskCtx.Env
}
@deprecated("Public API of JvmWorkerApi is deprecated.", "1.0.7")
trait JvmWorkerApi {

  @deprecated("Public API of JvmWorkerApi is deprecated.", "1.0.7")
  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(using ctx: JvmWorkerApi.Ctx): mill.api.Result[CompilationResult]

  @deprecated("Public API of JvmWorkerApi is deprecated.", "1.0.7")
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
  )(using ctx: JvmWorkerApi.Ctx): mill.api.Result[CompilationResult]

  @deprecated("Public API of JvmWorkerApi is deprecated.", "1.0.7")
  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean
}
