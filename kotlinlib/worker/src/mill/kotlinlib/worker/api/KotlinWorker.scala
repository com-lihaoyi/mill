/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.api

import mill.api.{Ctx, Result}

trait KotlinWorker {

  def compile(target: KotlinWorkerTarget, args: Seq[String])(implicit ctx: Ctx): Result[Unit]

}

sealed class KotlinWorkerTarget
object KotlinWorkerTarget {
  case object Jvm extends KotlinWorkerTarget
  case object Js extends KotlinWorkerTarget
}
