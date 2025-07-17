package mill.javalib.zinc

import mill.api.PathRef
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.{CompilationResult, JvmWorkerApi}
import mill.javalib.worker.JavaCompilerOptions

trait ZincWorkerApi {
  /** Compile a Java-only project */
  def compileJava(
    upstreamCompileOutput: Seq[CompilationResult],
    sources: Seq[os.Path],
    compileClasspath: Seq[os.Path],
    javacOptions: JavaCompilerOptions,
    reporter: Option[CompileProblemReporter],
    reportCachedProblems: Boolean,
    incrementalCompilation: Boolean
  )(using ctx: JvmWorkerApi.Ctx): mill.api.Result[CompilationResult]

  /** Compile a mixed Scala/Java or Scala-only project */
  def compileMixed(
    upstreamCompileOutput: Seq[CompilationResult],
    sources: Seq[os.Path],
    compileClasspath: Seq[os.Path],
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
  )(using ctx: JvmWorkerApi.Ctx): mill.api.Result[CompilationResult]

  def docJar(
    scalaVersion: String,
    scalaOrganization: String,
    compilerClasspath: Seq[PathRef],
    scalacPluginClasspath: Seq[PathRef],
    args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean
}
