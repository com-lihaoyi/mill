package mill.scalalib.api

import mill.api.{CompileProblemReporter, PathRef}

object ZincWorkerApi {
  type Ctx = mill.api.Ctx
}
trait ZincWorkerApi {

  /** Compile a Java-only project */
  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult]

  /** Compile a mixed Scala/Java or Scala-only project */
  def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
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
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult]

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      args: Seq[String]
  )(implicit ctx: ZincWorkerApi.Ctx): Boolean

}
