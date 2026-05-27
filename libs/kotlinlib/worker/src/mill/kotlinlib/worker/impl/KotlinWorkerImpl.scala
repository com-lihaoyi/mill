/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.impl

import mill.api.daemon.Result
import mill.api.TaskCtx
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}

class KotlinWorkerImpl extends KotlinWorker {
  private object RawPathSerializer extends os.Path.Serializer {
    def serializeString(p: os.Path): String = p.wrapped.toString
    def serializeFile(p: os.Path): java.io.File = p.wrapped.toFile
    def serializePath(p: os.Path): java.nio.file.Path = p.wrapped

    def deserialize(s: String): java.nio.file.Path = os.Path.defaultPathSerializer.deserialize(s)
    def deserialize(s: java.io.File): java.nio.file.Path =
      os.Path.defaultPathSerializer.deserialize(s)
    def deserialize(s: java.nio.file.Path): java.nio.file.Path =
      os.Path.defaultPathSerializer.deserialize(s)
    def deserialize(s: java.net.URI): java.nio.file.Path =
      os.Path.defaultPathSerializer.deserialize(s)
  }

  def compile(
      target: KotlinWorkerTarget,
      useBtApi: Boolean,
      args: Seq[String],
      sources: Seq[os.Path]
  )(using
      ctx: TaskCtx
  ): Result[Unit] = {
    ctx.log.debug(s"Using Kotlin compiler arguments: " +
      args.map(v => s"'${v}'").mkString(" "))

    ctx.log.debug(s"Using source files: ${sources.map(v => s"'${v}'").mkString(" ")}")

    // Use dedicated class to load implementation classes lazily
    val compiler = (target = target, useBtApi = useBtApi) match {
      case (KotlinWorkerTarget.Jvm, true) => JvmCompileBtApiImpl()
      case (KotlinWorkerTarget.Jvm, false) => JvmCompileImpl()
      case (target = KotlinWorkerTarget.Js) => JsCompileImpl()
    }

    ctx.log.debug(s"Using compiler backend: ${compiler.getClass().getSimpleName()}")

    val (exitCode, exitCodeName) =
      // Kotlin compiler internals and incremental caches require absolute paths.
      // Temporarily disable path relativization for this in-process compiler call.
      os.Path.pathSerializer.withValue(RawPathSerializer) {
        compiler.compile(args, sources)
      }

    if (exitCode != 0) {
      sys.error(s"Kotlin compiler failed with exit code ${exitCode} ($exitCodeName)")
    }
    ()

  }

}
