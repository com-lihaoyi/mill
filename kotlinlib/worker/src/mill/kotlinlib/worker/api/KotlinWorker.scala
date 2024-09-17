/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package mill.kotlinlib.worker.api

import mill.api.{Ctx, Result}

trait KotlinWorker {

  def compile(args: String*)(implicit ctx: Ctx): Result[Unit]

}
