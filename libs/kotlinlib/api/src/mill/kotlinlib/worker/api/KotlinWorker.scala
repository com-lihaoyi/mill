/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.api

import mill.api.{PathRef, TaskCtx}
import mill.api.daemon.Result

trait KotlinWorker {

  /**
   * Compile the given sources.
   * @param useBtApi Whether to use the Build Tools API. Only relevant for [[KotlinWorkerTarget.Jvm]].
   * @param kotlinVersion The Kotlin version being compiled against. Selects the Build Tools API
   *                      backend, since the API changed incompatibly in 2.4.0.
   * @param args Compiler arguments.
   * @param sources Source files to compile.
   * @param classpath Compilation classpath, useful for incremental compilation.
   */
  def compile(
      target: KotlinWorkerTarget,
      useBtApi: Boolean,
      kotlinVersion: String,
      args: Seq[String],
      sources: Seq[os.Path],
      classpath: Seq[PathRef]
  )(using ctx: TaskCtx): Result[Unit]

}
