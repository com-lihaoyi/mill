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
  private def absString(p: os.Path): String = p.toIO.getAbsolutePath

  private def absolutizePathLike(path: String, workspaceRoot: os.Path): String = {
    if (path.isEmpty) path
    else {
      val nio = java.nio.file.Path.of(path)
      val deserialized = os.Path.pathSerializer.value.deserialize(nio)
      if (deserialized.isAbsolute) deserialized.toString
      else workspaceRoot.toIO.toPath.resolve(deserialized).normalize().toAbsolutePath.toString
    }
  }

  private def absolutizeArgs(args: Seq[String], workspaceRoot: os.Path): Seq[String] = {
    val pathFlags = Set("-d", "-classpath", "-cp")
    val b = Seq.newBuilder[String]
    var i = 0
    while (i < args.length) {
      val arg = args(i)
      b += arg
      if (pathFlags(arg) && i + 1 < args.length) {
        val next = args(i + 1)
        val normalized =
          if (arg == "-classpath" || arg == "-cp") {
            next
              .split(java.io.File.pathSeparator, -1)
              .map(p => if (p.nonEmpty) absolutizePathLike(p, workspaceRoot) else p)
              .mkString(java.io.File.pathSeparator)
          } else absolutizePathLike(next, workspaceRoot)
        b += normalized
        i += 2
      } else i += 1
    }
    b.result()
  }

  def compile(args: Seq[String], sources: Seq[os.Path])(using ctx: TaskCtx): (Int, String) = {

    val workspaceAlias = os.pwd / os.sub / "out/mill-workspace"
    val effectiveWorkspace =
      if (os.exists(workspaceAlias)) workspaceAlias
      else ctx.workspace
    val effectiveDest =
      if (os.exists(workspaceAlias) && ctx.dest.startsWith(ctx.workspace))
        workspaceAlias / ctx.dest.subRelativeTo(ctx.workspace)
      else ctx.dest

    val incrementalCachePath = effectiveDest / "inc-state"
    os.makeDir.all(incrementalCachePath)

    val service = CompilationService.loadImplementation(getClass().getClassLoader())

    val strategyConfig = service.makeCompilerExecutionStrategyConfiguration()

    val compilationConfig = service.makeJvmCompilationConfiguration().tap { conf =>
      val incrementalConfig =
        conf.makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
      incrementalConfig.setRootProjectDir(effectiveWorkspace.toIO.getAbsoluteFile)
      incrementalConfig.usePreciseJavaTracking(true)
      incrementalConfig.setBuildDir(incrementalCachePath.toIO.getAbsoluteFile)

      // Create approach parameters for classpath snapshot-based incremental compilation.
      // Pass empty list for newClasspathSnapshotFiles to let the API compute snapshots from classpath JARs.
      // See: https://github.com/JetBrains/kotlin/blob/v2.1.20/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/K2JVMCompileMojo.java#L356
      val approachParams = new ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
        java.util.Collections.emptyList(),
        (incrementalCachePath / "shrunk-classpath-snapshot.bin").toIO.getAbsoluteFile
      )

      conf.useIncrementalCompilation(
        incrementalCachePath.toIO.getAbsoluteFile,
        SourcesChanges.ToBeCalculated.INSTANCE,
        approachParams,
        incrementalConfig
      )
    }

    // Use a deterministic project ID based on the task destination path
    // This ensures the Build Tools API can track incremental state across compilations
    val projectId = new ProjectId.ProjectUUID(
      UUID.nameUUIDFromBytes(effectiveDest.toString.getBytes)
    )

    // Pass sources to the Build Tools API for proper incremental compilation tracking
    // The API uses this list to detect changes between compilations
    val sourceFiles = sources.map(_.toIO.getAbsoluteFile).toArray
    val normalizedArgs = absolutizeArgs(args, effectiveWorkspace)

    val compilationResult = service.compileJvm(
      projectId,
      strategyConfig,
      compilationConfig,
      KotlinInterop.toKotlinList(sourceFiles),
      KotlinInterop.toKotlinList(sourceFiles.map(_.getPath) ++ normalizedArgs.toArray)
    )

    val exitCode = compilationResult match {
      case CompilationResult.COMPILATION_SUCCESS => ExitCode.OK
      case CompilationResult.COMPILATION_ERROR => ExitCode.COMPILATION_ERROR
      case CompilationResult.COMPILATION_OOM_ERROR => ExitCode.OOM_ERROR
      case CompilationResult.COMPILER_INTERNAL_ERROR => ExitCode.INTERNAL_ERROR
    }

    // This seems to cause sporadic crashes, so just commenting it out for now.
    // It seems it does global cleanup that may cause issues when there are
    // multiple modules compiling in parallel, e.g. one module's cleanup may
    // interfere with another and cause crashes https://github.com/com-lihaoyi/mill/issues/6427
    //
    // service.finishProjectCompilation(projectId)

    (exitCode.getCode(), exitCode.name())
  }

}
