package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.buildtools.api.{
  CompilationResult,
  CompilationService,
  ProjectId,
  SourcesChanges
}
import org.jetbrains.kotlin.buildtools.api.jvm.ClasspathSnapshotBasedIncrementalCompilationApproachParameters
import org.jetbrains.kotlin.cli.common.ExitCode

import java.util.UUID
import scala.util.chaining.scalaUtilChainingOps

class JvmCompileBtApiImpl() extends Compiler {

  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(using
      ctx: TaskCtx
  ): (Int, String) = {

    val incrementalCachePath = ctx.dest / "inc-state"
    os.makeDir.all(incrementalCachePath)

    val service = CompilationService.loadImplementation(getClass().getClassLoader())

    val strategyConfig = service.makeCompilerExecutionStrategyConfiguration()

    val compilationConfig = service.makeJvmCompilationConfiguration().tap { conf =>
      // Create incremental compilation configuration
      val incrementalConfig =
        conf.makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
      incrementalConfig.setRootProjectDir(ctx.workspace.toIO)
      incrementalConfig.usePreciseJavaTracking(true)
      incrementalConfig.setBuildDir(incrementalCachePath.toIO)

      // Create approach parameters for classpath snapshot-based incremental compilation
      val approachParams = new ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
        java.util.Collections.emptyList(), // newClasspathSnapshotFiles - empty, let the API manage
        (incrementalCachePath / "shrunk-classpath-snapshot.bin").toIO
      )

      // Enable incremental compilation
      // SourcesChanges.ToBeCalculated tells the API to detect changes automatically
      conf.useIncrementalCompilation(
        incrementalCachePath.toIO,
        SourcesChanges.ToBeCalculated.INSTANCE,
        approachParams,
        incrementalConfig
      )
    }

    // Use a deterministic project ID based on the task destination path
    // This ensures the Build Tools API can track incremental state across compilations
    val projectId = new ProjectId.ProjectUUID(
      UUID.nameUUIDFromBytes(ctx.dest.toString.getBytes)
    )

    // Pass sources to the Build Tools API for proper incremental compilation tracking
    // The API uses this list to detect changes between compilations
    val sourceFiles = sources.map(_.toIO).toArray

    val compilationResult = service.compileJvm(
      projectId,
      strategyConfig,
      compilationConfig,
      KotlinInterop.toKotlinList(sourceFiles),
      KotlinInterop.toKotlinList(args.toArray)
    )

    val exitCode = compilationResult match {
      case CompilationResult.COMPILATION_SUCCESS => ExitCode.OK
      case CompilationResult.COMPILATION_ERROR => ExitCode.COMPILATION_ERROR
      case CompilationResult.COMPILATION_OOM_ERROR => ExitCode.OOM_ERROR
      case CompilationResult.COMPILER_INTERNAL_ERROR => ExitCode.INTERNAL_ERROR
    }

    // Do we really need to call this (after each compilation)?
    service.finishProjectCompilation(projectId)

    (exitCode.getCode(), exitCode.name())
  }

}
