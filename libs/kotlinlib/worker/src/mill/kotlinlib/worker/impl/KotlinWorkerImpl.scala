/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.impl

import mill.api.{Result, Task, TaskCtx}
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}

class KotlinWorkerImpl extends KotlinWorker {

  def compile(
      target: KotlinWorkerTarget,
      args: Seq[String],
      sources: Seq[os.Path]
  )(implicit
      ctx: TaskCtx
  ): Result[Unit] = {
    ctx.log.debug("Using kotlin compiler arguments: " + args.map(v => s"'${v}'").mkString(" "))

    val (exitCode, exitCodeName) = target match {

      case KotlinWorkerTarget.Jvm =>
        // Use dedicated class to load classes lazily
        JvmCompileImpl().compile(args, sources)

      case KotlinWorkerTarget.Js =>
        // Use dedicated class to load classes lazily
        JsCompileImpl().compile(args, sources)

    }

    if (exitCode != 0) {
      Task.fail(s"Kotlin compiler failed with exit code ${exitCode} ($exitCodeName)")
    }
    ()

  }

}
