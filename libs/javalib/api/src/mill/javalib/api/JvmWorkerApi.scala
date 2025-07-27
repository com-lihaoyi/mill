package mill.javalib.api

import mill.api.Result
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.internal.{
  JavaRuntimeOptions,
  ZincCompileJava,
  ZincCompileMixed,
  ZincScaladocJar
}

object JvmWorkerApi {
  type Ctx = mill.api.TaskCtx.Dest & mill.api.TaskCtx.Log & mill.api.TaskCtx.Env
}
trait JvmWorkerApi {
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
    javaHome: Option[os.Path],
  )(using context: JvmWorkerApi.Ctx): Boolean
}
