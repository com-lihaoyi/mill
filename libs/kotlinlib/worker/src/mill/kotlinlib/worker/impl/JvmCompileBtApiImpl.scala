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
import java.util.concurrent.ConcurrentHashMap
import scala.util.chaining.given
import scala.collection.mutable

class JvmCompileBtApiImpl() extends Compiler {

  private var compilationService: Option[CompilationService] = Option.empty

  // we synchronize cleanup on this field, so we don't start new jobs while we're cleaning
  private val runningJobs = mutable.HashSet[Object]()
  private val projectIds = ConcurrentHashMap.newKeySet[ProjectId.ProjectUUID]()

  def compile(args: Seq[String], sources: Seq[os.Path])(using ctx: TaskCtx): (Int, String) = {
    val job = new Object()
    // synchronize, to wait for a potential cleanup
    runningJobs.synchronized {
      runningJobs.add(job)
    }
    try {
      val incrementalCachePath = ctx.dest / "inc-state"
      os.makeDir.all(incrementalCachePath)

      val service: CompilationService = synchronized {
        compilationService.getOrElse {
          CompilationService.loadImplementation(getClass().getClassLoader())
            .tap { s => compilationService = Some(s) }
        }
      }

      val strategyConfig = service.makeCompilerExecutionStrategyConfiguration()

      val compilationConfig = service.makeJvmCompilationConfiguration().tap { conf =>
        val incrementalConfig =
          conf.makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
        incrementalConfig.setRootProjectDir(ctx.workspace.toIO)
        incrementalConfig.usePreciseJavaTracking(true)
        incrementalConfig.setBuildDir(incrementalCachePath.toIO)

        // Create approach parameters for classpath snapshot-based incremental compilation.
        // Pass empty list for newClasspathSnapshotFiles to let the API compute snapshots from classpath JARs.
        // See: https://github.com/JetBrains/kotlin/blob/v2.1.20/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/K2JVMCompileMojo.java#L356
        val approachParams = new ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
          java.util.Collections.emptyList(),
          (incrementalCachePath / "shrunk-classpath-snapshot.bin").toIO
        )

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
      projectIds.add(projectId)

      // Pass sources to the Build Tools API for proper incremental compilation tracking
      // The API uses this list to detect changes between compilations
      val sourceFiles = sources.map(_.toIO).toArray

      val compilationResult = service.compileJvm(
        projectId,
        strategyConfig,
        compilationConfig,
        KotlinInterop.toKotlinList(sourceFiles),
        KotlinInterop.toKotlinList(sourceFiles.map(_.toString) ++ args.toArray)
      )

      val exitCode = compilationResult match {
        case CompilationResult.COMPILATION_SUCCESS => ExitCode.OK
        case CompilationResult.COMPILATION_ERROR => ExitCode.COMPILATION_ERROR
        case CompilationResult.COMPILATION_OOM_ERROR => ExitCode.OOM_ERROR
        case CompilationResult.COMPILER_INTERNAL_ERROR => ExitCode.INTERNAL_ERROR
      }

      (exitCode.getCode(), exitCode.name())

    } finally {
      runningJobs.synchronized {
        runningJobs.remove(job)
      }
      cleanup()
    }
  }

  private def cleanup(): Unit = {
    if (!projectIds.isEmpty() && runningJobs.isEmpty) {
      // synchronize, to not clean while we compile
      runningJobs.synchronized {
        if (runningJobs.isEmpty) {
          projectIds.forEach { id =>
            // According to the API docs, this must be called when all compilations are done.
            // But we need to make sure, that we don't run it while we still have some compilations going on,
            // to avoid crashes like https://github.com/com-lihaoyi/mill/issues/6427
            compilationService.foreach(_.finishProjectCompilation(id))
          }
          projectIds.clear()
        }
      }
    }
  }

  override def close(): Unit = {
    cleanup()
  }

}
