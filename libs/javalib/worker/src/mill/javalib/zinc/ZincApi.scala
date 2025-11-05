package mill.javalib.zinc

import mill.api.Result
import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.{ZincCompileJava, ZincCompileMixed, ZincOperation, ZincScaladocJar}

/** Gives you API for the Zinc incremental compiler. */
trait ZincApi {

  /** Compile a Java-only project. */
  def apply(
      op: ZincOperation,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  ): op.Response

}
