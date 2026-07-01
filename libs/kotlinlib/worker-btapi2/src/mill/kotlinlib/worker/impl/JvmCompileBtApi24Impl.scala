package mill.kotlinlib.worker.impl

import mill.api.{PathRef, TaskCtx}
import org.jetbrains.kotlin.buildtools.api.{BuildOperation, CompilationResult, SourcesChanges}
import org.jetbrains.kotlin.buildtools.api.jvm.{
  ClasspathEntrySnapshot,
  ClassSnapshotGranularity,
  JvmPlatformToolchain
}
import org.jetbrains.kotlin.buildtools.api.jvm.operations.{
  JvmClasspathSnapshottingOperation,
  JvmCompilationOperation
}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * [[BtApiCompiler]] backend for Kotlin 2.4.0+, which replaced the legacy operation factories with
 * the builders introduced in 2.3.20. Compiled against the 2.4 Build Tools API and classloaded by
 * [[KotlinWorkerImpl]] so the two incompatible API generations never share a compilation classpath.
 */
class JvmCompileBtApi24Impl(classpathSnapshotCache: os.Path)
    extends BtApiCompiler(classpathSnapshotCache) {

  protected def compilationOperation(
      jvmToolchain: JvmPlatformToolchain,
      sourceFiles: java.util.List[Path],
      destinationDirectory: os.Path,
      args: Seq[String],
      incrementalCachePath: os.Path,
      classpathSnapshotFiles: Seq[Path]
  )(using ctx: TaskCtx): BuildOperation[CompilationResult] = {
    val builder = jvmToolchain.jvmCompilationOperationBuilder(
      sourceFiles,
      PathRef.toAbsNioPath(destinationDirectory)
    )
    builder.getCompilerArguments().applyArgumentStrings(withoutDestinationArgument(args).asJava)

    val icBuilder = builder.snapshotBasedIcConfigurationBuilder(
      PathRef.toAbsNioPath(incrementalCachePath),
      SourcesChanges.ToBeCalculated.INSTANCE,
      classpathSnapshotFiles.asJava,
      PathRef.toAbsNioPath(incrementalCachePath / "shrunk-classpath-snapshot.bin")
    )
    import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration as Ic
    icBuilder.set(Ic.ROOT_PROJECT_DIR, PathRef.toAbsNioPath(ctx.workspace))
    icBuilder.set(Ic.MODULE_BUILD_DIR, PathRef.toAbsNioPath(incrementalCachePath))
    icBuilder.set(Ic.PRECISE_JAVA_TRACKING, java.lang.Boolean.TRUE)

    builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icBuilder.build())
    builder.build()
  }

  protected def snapshottingOperation(
      jvmToolchain: JvmPlatformToolchain,
      classpathEntry: Path
  )(using ctx: TaskCtx): BuildOperation[ClasspathEntrySnapshot] = {
    val builder = jvmToolchain.classpathSnapshottingOperationBuilder(classpathEntry)
    builder.set(JvmClasspathSnapshottingOperation.GRANULARITY, ClassSnapshotGranularity.CLASS_MEMBER_LEVEL)
    builder.set(JvmClasspathSnapshottingOperation.PARSE_INLINED_LOCAL_CLASSES, java.lang.Boolean.TRUE)
    builder.build()
  }

  // Kotlin 2.4.0 rejects a redundant `-d` arg (a hard error from 2.5.0); the destination is
  // passed to the builder explicitly, so strip it (and its value) from the argument strings.
  private def withoutDestinationArgument(args: Seq[String]): Seq[String] = {
    val (result, _) = args.foldLeft((Vector.empty[String], false)) {
      case ((acc, true), _) => (acc, false)
      case ((acc, false), "-d") => (acc, true)
      case ((acc, false), arg) => (acc :+ arg, false)
    }
    result
  }
}
