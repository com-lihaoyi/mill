package mill.kotlinlib

import mill.{Agg, T}
import mill.api.{PathRef, Result}
import mill.define.{ModuleRef, Task}
import mill.scalalib.api.{CompilationResult, ZincWorkerApi}
import mill.scalalib.{Dep, JavaModule, ZincWorkerModule}

trait KotlinModulePlatform extends JavaModule {

  type CompileProblemReporter = mill.api.CompileProblemReporter

  protected def zincWorkerRef: ModuleRef[ZincWorkerModule] = zincWorker

  protected def kotlinWorkerRef: ModuleRef[KotlinWorkerModule] = ModuleRef(KotlinWorkerModule)

  def kotlinCompilerIvyDeps: T[Agg[Dep]]

  /**
   * The Java classpath resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerIvyDeps]].
   */
  def kotlinCompilerClasspath: T[Seq[PathRef]] = T {
    resolveDeps(T.task { kotlinCompilerIvyDeps().map(bindDependency()) })().toSeq
  }

  private[kotlinlib] def internalCompileJavaFiles(
    worker: ZincWorkerApi,
    upstreamCompileOutput: Seq[CompilationResult],
    javaSourceFiles: Seq[os.Path],
    compileCp: Agg[os.Path],
    javacOptions: Seq[String],
    compileProblemReporter: Option[CompileProblemReporter],
    reportOldProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): Result[CompilationResult] = {
    worker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = javaSourceFiles,
      compileClasspath = compileCp,
      javacOptions = javacOptions,
      reporter = compileProblemReporter,
      reportCachedProblems = reportOldProblems
    )
  }

  private[kotlinlib] def internalReportOldProblems: Task[Boolean] = zincReportCachedProblems

}
