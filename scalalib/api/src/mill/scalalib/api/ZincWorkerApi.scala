package mill.scalalib.api

import mill.api.{CompileProblemReporter, PathRef}
import mill.api.Loose.Agg

import scala.annotation.nowarn

object ZincWorkerApi {
  type Ctx = mill.api.Ctx.Dest with mill.api.Ctx.Log with mill.api.Ctx.Home
}
trait ZincWorkerApi {

  /** Compile a Java-only project */
  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] =
    compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javacOptions = javacOptions,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems
    ): @nowarn("cat=deprecation")

  /** Compile a Java-only project */
  @deprecated("Use override with `incrementalCompilation` parameter", "Mill 0.11.6")
  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] =
    compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javacOptions = javacOptions,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems,
      incrementalCompilation = true
    )

  /** Compile a mixed Scala/Java or Scala-only project */
  def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String]
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] =
    compileMixed(
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

  /** Compile a mixed Scala/Java or Scala-only project */
  @deprecated("Use override with `incrementalCompilation` parameter", "Mill 0.11.6")
  def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] =
    compileMixed(
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
      incrementalCompilation = true,
      auxiliaryClassFileExtensions = Seq.empty[String]
    )

  /** Compile a mixed Scala/Java or Scala-only project */
  @deprecated("Use override with `auxiliaryClassFileExtensions` parameter", "Mill 0.11.8")
  def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] =
    compileMixed(
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
      auxiliaryClassFileExtensions = Seq.empty[String]
    )

  /**
   * Find main classes by inspecting the Zinc compilation analysis file.
   */
  def discoverMainClasses(compilationResult: CompilationResult): Seq[String]

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      args: Seq[String]
  )(implicit ctx: ZincWorkerApi.Ctx): Boolean

  /**
   * Discover main classes by inspecting the classpath.
   */
  def discoverMainClasses(classpath: Seq[os.Path]): Seq[String] = {
    // We need this default-impl to keep binary compatinility (0.11.x)
    Seq.empty
  }
}
