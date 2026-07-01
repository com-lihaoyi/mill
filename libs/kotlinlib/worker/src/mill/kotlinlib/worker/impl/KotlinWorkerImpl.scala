/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.impl

import mill.api.daemon.Result
import mill.api.TaskCtx
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}

class KotlinWorkerImpl(
    private val classpathSnapshotCache: os.Path,
    private val classpathSnapshotCacheIsStable: Boolean
) extends KotlinWorker, AutoCloseable {
  def compile(
      target: KotlinWorkerTarget,
      useBtApi: Boolean,
      kotlinVersion: String,
      args: Seq[String],
      sources: Seq[os.Path],
      classpath: Seq[mill.api.PathRef]
  )(using
      ctx: TaskCtx
  ): Result[Unit] = {
    ctx.log.debug(s"Using Kotlin compiler arguments: " +
      args.map(v => s"'${v}'").mkString(" "))

    ctx.log.debug(s"Using source files: ${sources.map(v => s"'${v}'").mkString(" ")}")

    // Kotlin 2.4.0 replaced the legacy Build Tools API operation factories with builders, so each
    // API generation has its own `Compiler`. The 2.4 backend lives in a sibling module compiled
    // against the 2.4 API; classload it (rather than link statically) to keep the two generations
    // off a shared compilation classpath.
    val compiler = (target = target, useBtApi = useBtApi) match {
      case (KotlinWorkerTarget.Jvm, true) =>
        if (usesBuilderApi(kotlinVersion))
          getClass().getClassLoader()
            .loadClass("mill.kotlinlib.worker.impl.JvmCompileBtApi24Impl")
            .getConstructor(classOf[os.Path])
            .newInstance(classpathSnapshotCache)
            .asInstanceOf[Compiler]
        else JvmCompileBtApiImpl(classpathSnapshotCache)
      case (KotlinWorkerTarget.Jvm, false) => JvmCompileImpl()
      case (target = KotlinWorkerTarget.Js) => JsCompileImpl()
    }

    ctx.log.debug(s"Using compiler backend: ${compiler.getClass().getSimpleName()}")

    val (exitCode, exitCodeName) = compiler.compile(args, sources, classpath)

    if (exitCode != 0) {
      sys.error(s"Kotlin compiler failed with exit code ${exitCode} ($exitCodeName)")
    }
    ()

  }

  override def close(): Unit = {
    if (!classpathSnapshotCacheIsStable) {
      os.remove.all(classpathSnapshotCache)
    }
  }

  private def usesBuilderApi(kotlinVersion: String): Boolean = {
    val Seq(major, minor) =
      kotlinVersion.split("[.-]").take(2).flatMap(_.toIntOption).toSeq.padTo(2, 0)
    major > 2 || (major == 2 && minor >= 4)
  }
}
