package mill.javalib.zinc

import mill.api.Result
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.{ZincCompileJava, ZincCompileMixed, ZincScaladocJar}

/** Gives you API for the Zinc incremental compiler. */
trait ZincApi {

  /** Compile a Java-only project. */
  def compileJava(
    op: ZincCompileJava,
    reporter: Option[CompileProblemReporter],
    reportCachedProblems: Boolean
  ): Result[CompilationResult]

  /** Compile a mixed Scala/Java or Scala-only project. */
  def compileMixed(
    op: ZincCompileMixed,
    reporter: Option[CompileProblemReporter],
    reportCachedProblems: Boolean
  ): Result[CompilationResult]

  /** Compiles a Scaladoc jar. */
  def scaladocJar(
      op: ZincScaladocJar,
  ): Boolean
}
