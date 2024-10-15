/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package mill.kotlinlib.worker.api

import mill.api.{Ctx, Result}

trait KotlinWorker {

  def compile(target: KotlinWorkerTarget, args: String*)(implicit ctx: Ctx): Result[Unit]

}

sealed class KotlinWorkerTarget
object KotlinWorkerTarget {
  case object Jvm extends KotlinWorkerTarget
  case object Js extends KotlinWorkerTarget
}
