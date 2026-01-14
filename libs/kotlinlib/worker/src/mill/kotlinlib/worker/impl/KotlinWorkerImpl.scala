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

    val (exitCode, exitCodeName) = compiler.compile(args, sources)

    if (exitCode != 0) {
      sys.error(s"Kotlin compiler failed with exit code ${exitCode} ($exitCodeName)")
    }
    ()

  }

}
