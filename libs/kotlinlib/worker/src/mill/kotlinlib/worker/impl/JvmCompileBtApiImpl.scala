package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.buildtools.api.{CompilationResult, CompilationService, ProjectId}
import org.jetbrains.kotlin.cli.common.ExitCode

import java.util.UUID
import scala.util.chaining.scalaUtilChainingOps

class JvmCompileBtApiImpl() extends Compiler {

  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(implicit
      ctx: TaskCtx
  ): (Int, String) = {

//    System.setProperty("kotlin.build-tools-api.log.level", "debug")

    val incrementalCompilerStatePath = ctx.dest / "inc-state"

    val service = CompilationService.loadImplementation(getClass().getClassLoader())

    val strategyConfig = service.makeCompilerExecutionStrategyConfiguration()

    val compilationConfig = service.makeJvmCompilationConfiguration().tap { conf =>
      val incrementalConfig =
        conf.makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
      incrementalConfig.setRootProjectDir(ctx.workspace.toIO)
      incrementalConfig.usePreciseJavaTracking(true)
      incrementalConfig.setBuildDir(incrementalCompilerStatePath.toIO)
    }

    val projectId = new ProjectId.ProjectUUID(UUID.randomUUID())

    val allArgsWithSources = args ++ sources.map(_.toString)

    val compilationResult = service.compileJvm(
      projectId,
      strategyConfig,
      compilationConfig,
//      KotlinInterop.toKotlinList(sources.map(_.toIO).toArray),
//      KotlinInterop.toKotlinList(args.toArray)
      KotlinInterop.toKotlinList(Array[java.io.File]()),
      KotlinInterop.toKotlinList(allArgsWithSources.toArray)
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
