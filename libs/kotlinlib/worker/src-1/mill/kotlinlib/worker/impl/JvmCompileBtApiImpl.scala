package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.buildtools.api.{BuildOperation, CompilationResult, SourcesChanges}
import org.jetbrains.kotlin.buildtools.api.jvm.{
  ClasspathEntrySnapshot,
  ClassSnapshotGranularity,
  JvmPlatformToolchain,
  JvmSnapshotBasedIncrementalCompilationConfiguration
}
import org.jetbrains.kotlin.buildtools.api.jvm.operations.{
  JvmClasspathSnapshottingOperation,
  JvmCompilationOperation
}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

/**
 * [[BtApiCompiler]] backend for Kotlin versions before 2.4.0, using the legacy operation factories.
 */
class JvmCompileBtApiImpl(classpathSnapshotCache: os.Path)
    extends BtApiCompiler(classpathSnapshotCache) {

  protected def compilationOperation(
      jvmToolchain: JvmPlatformToolchain,
      sourceFiles: java.util.List[Path],
      destinationDirectory: os.Path,
      args: Seq[String],
      incrementalCachePath: os.Path,
      classpathSnapshotFiles: Seq[Path]
  )(using ctx: TaskCtx): BuildOperation[CompilationResult] = {
    val operation = jvmToolchain.createJvmCompilationOperation(
      sourceFiles,
      destinationDirectory.toNIO
    )
    operation.getCompilerArguments().applyArgumentStrings(args.asJava)

    val snapshotIcOptions = operation.createSnapshotBasedIcOptions().tap { options =>
      import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions as Ic
      options.set(Ic.ROOT_PROJECT_DIR, ctx.workspace.toNIO)
      options.set(Ic.MODULE_BUILD_DIR, incrementalCachePath.toNIO)
      options.set(Ic.PRECISE_JAVA_TRACKING, java.lang.Boolean.TRUE)
    }

    operation.set(
      JvmCompilationOperation.INCREMENTAL_COMPILATION,
      JvmSnapshotBasedIncrementalCompilationConfiguration(
        incrementalCachePath.toNIO,
        SourcesChanges.ToBeCalculated.INSTANCE,
        classpathSnapshotFiles.asJava,
        (incrementalCachePath / "shrunk-classpath-snapshot.bin").toNIO,
        snapshotIcOptions
      )
    )
    operation
  }

  protected def snapshottingOperation(
      jvmToolchain: JvmPlatformToolchain,
      classpathEntry: Path
  )(using ctx: TaskCtx): BuildOperation[ClasspathEntrySnapshot] = {
    val operation = jvmToolchain.createClasspathSnapshottingOperation(classpathEntry)
    operation.set(
      JvmClasspathSnapshottingOperation.GRANULARITY,
      ClassSnapshotGranularity.CLASS_MEMBER_LEVEL
    )
    operation.set(
      JvmClasspathSnapshottingOperation.PARSE_INLINED_LOCAL_CLASSES,
      java.lang.Boolean.TRUE
    )
    operation
  }
}
