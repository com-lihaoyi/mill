package mill.kotlinlib.worker.impl

import mill.api.{PathRef, TaskCtx}
import org.jetbrains.kotlin.buildtools.api.{
  CompilationResult,
  ExecutionPolicy,
  KotlinLogger,
  KotlinToolchains,
  SourcesChanges
}
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.cli.common.ExitCode
import mill.kotlinlib.worker.api.renderIntAsHex

import java.io.{PrintWriter, StringWriter}
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

/**
 * @param classpathSnapshotCache The path to store classpath snapshots for incremental compilation.
 *                               Should live longer than the compile task itself, i.e. within a Worker
 *                               or a persistent task.
 */
class JvmCompileBtApiImpl(
    val classpathSnapshotCache: os.Path
) extends Compiler {

  private def formatThrowable(throwable: Throwable): String = {
    val sw = StringWriter()
    throwable.printStackTrace(PrintWriter(sw))
    sw.toString()
  }

  private def kotlinLogger(using ctx: TaskCtx): KotlinLogger = new KotlinLogger {
    override def isDebugEnabled(): Boolean = ctx.log.debugEnabled

    override def error(message: String, throwable: Throwable): Unit = {
      if (throwable == null) ctx.log.error(message)
      else ctx.log.error(s"${message}\n${formatThrowable(throwable)}")
    }

    override def warn(message: String, throwable: Throwable): Unit = {
      if (throwable == null) ctx.log.warn(message)
      else ctx.log.warn(s"${message}\n${formatThrowable(throwable)}")
    }

    override def info(message: String): Unit = ctx.log.info(message)

    override def debug(message: String): Unit = ctx.log.debug(message)

    override def lifecycle(message: String): Unit = ctx.log.info(message)
  }

  private def destinationDirectoryFromArgs(args: Seq[String])(using ctx: TaskCtx): os.Path = {
    args.sliding(2)
      .collectFirst {
        case Seq("-d", dir) => os.Path(dir, ctx.workspace)
      }
      .getOrElse(ctx.dest / "classes")
  }

  def compile(
      args: Seq[String],
      sources: Seq[os.Path],
      classpath: Seq[PathRef]
  )(using ctx: TaskCtx): (Int, String) = {

    val incrementalCachePath = ctx.dest / "inc-state"
    os.makeDir.all(incrementalCachePath)
    val destinationDirectory = destinationDirectoryFromArgs(args)

    val toolchains = KotlinToolchains.loadImplementation(getClass().getClassLoader())
    val jvmToolchain = JvmPlatformToolchain.from(toolchains)
    // The BTAPI IC engine (RelocatableFileToPathConverter) rejects the relative
    // `../mill-workspace/...` paths os-lib renders inside Mill's sandbox, so every
    // path handed to it is resolved to a real absolute path via `PathRef.toAbsNioPath`.
    val sourceFiles = sources.map(PathRef.toAbsNioPath(_)).asJava
    val compilationOperation =
      jvmToolchain.createJvmCompilationOperation(
        sourceFiles,
        PathRef.toAbsNioPath(destinationDirectory)
      )

    compilationOperation.getCompilerArguments().applyArgumentStrings(args.asJava)

    val snapshotIcOptions = compilationOperation.createSnapshotBasedIcOptions().tap { options =>
      options.set(
        JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR,
        PathRef.toAbsNioPath(ctx.workspace)
      )
      options.set(
        JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR,
        PathRef.toAbsNioPath(incrementalCachePath)
      )
      options.set(
        JvmSnapshotBasedIncrementalCompilationOptions.PRECISE_JAVA_TRACKING,
        java.lang.Boolean.TRUE
      )
    }

    os.makeDir.all(classpathSnapshotCache)

    val compilationResult = {
      val buildSession = toolchains.createBuildSession()
      val executionPolicy = toolchains.createInProcessExecutionPolicy()
      try {
        val classpathSnapshotFiles = classpath.filter(ref => os.exists(ref.path)).map { ref =>
          snapshot(jvmToolchain, buildSession, executionPolicy, ref)
        }

        val incrementalConfig = new JvmSnapshotBasedIncrementalCompilationConfiguration(
          PathRef.toAbsNioPath(incrementalCachePath),
          SourcesChanges.ToBeCalculated.INSTANCE,
          classpathSnapshotFiles.asJava,
          PathRef.toAbsNioPath(incrementalCachePath / "shrunk-classpath-snapshot.bin"),
          snapshotIcOptions
        )
        compilationOperation.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, incrementalConfig)

        buildSession.executeOperation(
          compilationOperation,
          executionPolicy,
          kotlinLogger
        )
      } finally {
        // The new Kotlin Toolchains API scopes lifecycle to BuildSession. We close the
        // per-compilation session and avoid old global project-finalization behavior that
        // previously caused parallel interference in Mill (see #6427).
        buildSession.close()
      }
    }

    val exitCode = compilationResult match {
      case CompilationResult.COMPILATION_SUCCESS => ExitCode.OK
      case CompilationResult.COMPILATION_ERROR => ExitCode.COMPILATION_ERROR
      case CompilationResult.COMPILATION_OOM_ERROR => ExitCode.OOM_ERROR
      case CompilationResult.COMPILER_INTERNAL_ERROR => ExitCode.INTERNAL_ERROR
    }

    (exitCode.getCode(), exitCode.name())
  }

  // A classpath entry's snapshot depends only on its content, so cache each
  // snapshot under the entry's `PathRef.sig`. A content change yields a new
  // `sig`, hence a new snapshot path, which both invalidates the cache and
  // makes the (potentially warm, daemon-resident) IC engine load the fresh
  // snapshot rather than a stale one cached by file path.
  private def snapshot(
      jvmToolchain: JvmPlatformToolchain,
      buildSession: KotlinToolchains.BuildSession,
      executionPolicy: ExecutionPolicy.InProcess,
      ref: PathRef
  )(using TaskCtx): java.nio.file.Path = {
    val snapshotFile =
      classpathSnapshotCache / s"${renderIntAsHex(ref.sig)}-${ref.path.last}.snapshot"
    if (!os.exists(snapshotFile)) {
      val snapshottingOperation =
        jvmToolchain.createClasspathSnapshottingOperation(PathRef.toAbsNioPath(ref.path))
      snapshottingOperation.set(
        JvmClasspathSnapshottingOperation.GRANULARITY,
        ClassSnapshotGranularity.CLASS_MEMBER_LEVEL
      )
      snapshottingOperation.set(
        JvmClasspathSnapshottingOperation.PARSE_INLINED_LOCAL_CLASSES,
        java.lang.Boolean.TRUE
      )
      val snapshot = buildSession.executeOperation(
        snapshottingOperation,
        executionPolicy,
        kotlinLogger
      )

      val tmpFile = os.temp(dir = classpathSnapshotCache, suffix = ".snapshot")
      snapshot.saveSnapshot(PathRef.toAbsNioPath(tmpFile).toFile)
      os.move(tmpFile, snapshotFile, atomicMove = true, replaceExisting = true)
    }
    PathRef.toAbsNioPath(snapshotFile)
  }
}
