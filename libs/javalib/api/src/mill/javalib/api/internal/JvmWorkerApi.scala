package mill.javalib.api.internal

import mill.api.{PathRef, Result}
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.api.JvmWorkerApi as PublicJvmWorkerApi

import scala.annotation.nowarn

//noinspection ScalaDeprecation
@nowarn("cat=deprecation")
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
}
object JvmWorkerApi {
  type Ctx = PublicJvmWorkerApi.Ctx
}
