package mill.javalib.api.internal

import mill.api.{PathRef, Result}
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.api.JvmWorkerApi as PublicJvmWorkerApi

trait JvmWorkerApi extends PublicJvmWorkerApi {

  /** Compile a Java-only project. */
  def compileJava(
      op: ZincCompileJava,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using context: JvmWorkerApi.Ctx): Result[CompilationResult]

  /** Compile a mixed Scala/Java or Scala-only project. */
  def compileMixed(
      op: ZincCompileMixed,
      javaHome: Option[os.Path],
      javaRuntimeOptions: JavaRuntimeOptions,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(using context: JvmWorkerApi.Ctx): Result[CompilationResult]

  /** Compiles a Scaladoc jar. */
  def scaladocJar(
      op: ZincScaladocJar,
      javaHome: Option[os.Path]
  )(using context: JvmWorkerApi.Ctx): Boolean

  // public API forwarder
  override def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(using ctx: PublicJvmWorkerApi.Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions(javacOptions)
    compileJava(
      ZincCompileJava(
        compileTo = ctx.dest,
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = jOpts.compiler,
        incrementalCompilation = incrementalCompilation
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
  )(using ctx: PublicJvmWorkerApi.Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions(javacOptions)
    compileMixed(
      ZincCompileMixed(
        compileTo = ctx.dest,
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
        auxiliaryClassFileExtensions = auxiliaryClassFileExtensions
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
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: PublicJvmWorkerApi.Ctx): Boolean = {
    scaladocJar(
      ZincScaladocJar(
        scalaVersion = scalaVersion,
        scalaOrganization = scalaOrganization,
        compilerClasspath = compilerClasspath,
        scalacPluginClasspath = scalacPluginClasspath,
        args = args
      ),
      javaHome = javaHome
    )
  }
}
object JvmWorkerApi {
  type Ctx = PublicJvmWorkerApi.Ctx
}
