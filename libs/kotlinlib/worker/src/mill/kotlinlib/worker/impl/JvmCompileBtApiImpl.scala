package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.buildtools.api.{
  CompilationResult,
  KotlinLogger,
  KotlinToolchains,
  SourcesChanges
}
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.cli.common.ExitCode

import java.io.{PrintWriter, StringWriter}
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

class JvmCompileBtApiImpl() extends Compiler {

  private def formatThrowable(throwable: Throwable): String = {
    val sw = new StringWriter()
    throwable.printStackTrace(new PrintWriter(sw))
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

  def compile(args: Seq[String], sources: Seq[os.Path])(using ctx: TaskCtx): (Int, String) = {

    val incrementalCachePath = ctx.dest / "inc-state"
    os.makeDir.all(incrementalCachePath)
    val destinationDirectory = destinationDirectoryFromArgs(args)

    val toolchains = KotlinToolchains.loadImplementation(getClass().getClassLoader())
    val jvmToolchain = JvmPlatformToolchain.from(toolchains)
    val sourceFiles = sources.map(_.toNIO).asJava
    val compilationOperation =
      jvmToolchain.createJvmCompilationOperation(sourceFiles, destinationDirectory.toNIO)

    compilationOperation.getCompilerArguments().applyArgumentStrings(args.asJava)

    val snapshotIcOptions = compilationOperation.createSnapshotBasedIcOptions().tap { options =>
      options.set(
        JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR,
        ctx.workspace.toNIO
      )
      options.set(
        JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR,
        incrementalCachePath.toNIO
      )
      options.set(
        JvmSnapshotBasedIncrementalCompilationOptions.PRECISE_JAVA_TRACKING,
        java.lang.Boolean.TRUE
      )
    }

    // Snapshot-based incremental compilation stores state in `inc-state`.
    // The empty snapshot list means Kotlin computes classpath snapshots from current classpath entries.
    val incrementalConfig = new JvmSnapshotBasedIncrementalCompilationConfiguration(
      incrementalCachePath.toNIO,
      SourcesChanges.ToBeCalculated.INSTANCE,
      Seq.empty[java.nio.file.Path].asJava,
      (incrementalCachePath / "shrunk-classpath-snapshot.bin").toNIO,
      snapshotIcOptions
    )
    compilationOperation.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, incrementalConfig)

    val compilationResult = {
      val buildSession = toolchains.createBuildSession()
      try {
        buildSession.executeOperation(
          compilationOperation,
          toolchains.createInProcessExecutionPolicy(),
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

}
