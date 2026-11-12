package mill.kotlinlib.worker.impl

import mill.api.{PathRef, TaskCtx}
import org.jetbrains.kotlin.buildtools.api.{
  BuildOperation,
  CompilationResult,
  ExecutionPolicy,
  KotlinLogger,
  KotlinToolchains
}
import org.jetbrains.kotlin.buildtools.api.jvm.{ClasspathEntrySnapshot, JvmPlatformToolchain}
import org.jetbrains.kotlin.cli.common.ExitCode
import mill.kotlinlib.worker.api.renderIntAsHex

import java.io.{PrintWriter, StringWriter}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.chaining.*

/**
 * Shared Build Tools API compilation flow. Implementations only supply the version-specific
 * construction of the compilation and classpath-snapshotting operations, since the API for
 * building those changed incompatibly in Kotlin 2.4.0.
 *
 * @param classpathSnapshotCache The path to store classpath snapshots for incremental compilation.
 *                               Should live longer than the compile task itself, i.e. within a Worker
 *                               or a persistent task.
 */
trait BtApiCompiler(val classpathSnapshotCache: os.Path) extends Compiler {

  protected def compilationOperation(
      jvmToolchain: JvmPlatformToolchain,
      sourceFiles: java.util.List[Path],
      destinationDirectory: os.Path,
      args: Seq[String],
      incrementalCachePath: os.Path,
      classpathSnapshotFiles: Seq[Path]
  )(using ctx: TaskCtx): BuildOperation[CompilationResult]

  protected def snapshottingOperation(
      jvmToolchain: JvmPlatformToolchain,
      classpathEntry: Path
  )(using ctx: TaskCtx): BuildOperation[ClasspathEntrySnapshot]

  protected def kotlinLogger(using ctx: TaskCtx): KotlinLogger = {
    // Kotlin uses file:///path instead of file:/path
    val workspaceUrl = ctx.workspace.toURL.toExternalForm()
      .replaceFirst("\\Qfile:/\\E([^/])", "file:///$1").pipe { u =>
        if (u.endsWith("/")) u else "$u/"
      }

    new KotlinLogger {
      private def mangleFileUrls(message: String): String = message
        .replaceFirst("^\\Q" + workspaceUrl + "\\E", "")

      override def isDebugEnabled(): Boolean = ctx.log.debugEnabled

      override def error(message: String, throwable: Throwable): Unit = {
        val msg = mangleFileUrls(message)
        if (throwable == null) ctx.log.error(msg)
        else ctx.log.error(s"${msg}\n${formatThrowable(throwable)}")
      }

      override def warn(message: String, throwable: Throwable): Unit = {
        val msg = mangleFileUrls(message)
        if (throwable == null) ctx.log.warn(msg)
        else ctx.log.warn(s"${msg}\n${formatThrowable(throwable)}")
      }

      override def info(message: String): Unit = ctx.log.info(mangleFileUrls(message))

      override def debug(message: String): Unit = ctx.log.debug(mangleFileUrls(message))

      override def lifecycle(message: String): Unit = ctx.log.info(mangleFileUrls(message))
    }
  }

  protected def destinationDirectoryFromArgs(args: Seq[String])(using ctx: TaskCtx): os.Path =
    args.sliding(2)
      .collectFirst { case Seq("-d", dir) => os.Path(dir, ctx.workspace) }
      .getOrElse(ctx.dest / "classes")

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
    val sourceFiles = sources.map(_.toNIO).asJava

    os.makeDir.all(classpathSnapshotCache)

    val compilationResult = {
      val buildSession = toolchains.createBuildSession()
      val executionPolicy = toolchains.createInProcessExecutionPolicy()
      try {
        val classpathSnapshotFiles = classpath.filter(ref => os.exists(ref.path)).map { ref =>
          snapshot(jvmToolchain, buildSession, executionPolicy, ref)
        }

        buildSession.executeOperation(
          compilationOperation(
            jvmToolchain = jvmToolchain,
            sourceFiles = sourceFiles,
            destinationDirectory = destinationDirectory,
            args = args,
            incrementalCachePath = incrementalCachePath,
            classpathSnapshotFiles = classpathSnapshotFiles
          ),
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
  )(using TaskCtx): Path = {
    val snapshotFile =
      classpathSnapshotCache / s"${renderIntAsHex(ref.sig)}-${ref.path.last}.snapshot"
    if (!os.exists(snapshotFile)) {
      val snapshot = buildSession.executeOperation(
        snapshottingOperation(jvmToolchain, ref.path.toNIO),
        executionPolicy,
        kotlinLogger
      )

      val tmpFile = os.temp(dir = classpathSnapshotCache, suffix = ".snapshot")
      snapshot.saveSnapshot(tmpFile.toIO)
      os.move(tmpFile, snapshotFile, atomicMove = true, replaceExisting = true)
    }
    snapshotFile.toNIO
  }

  private def formatThrowable(throwable: Throwable): String = {
    val sw = StringWriter()
    throwable.printStackTrace(PrintWriter(sw))
    sw.toString()
  }
}
