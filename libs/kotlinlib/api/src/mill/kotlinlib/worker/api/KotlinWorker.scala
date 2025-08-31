/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.api

import mill.api.TaskCtx
import mill.api.Result

trait KotlinWorker {

  def compile(
      target: KotlinWorkerTarget,
      args: Seq[String],
      sources: Seq[os.Path]
  )(implicit ctx: TaskCtx): Result[Unit]

}
