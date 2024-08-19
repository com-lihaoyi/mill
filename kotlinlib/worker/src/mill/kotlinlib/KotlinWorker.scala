package mill.kotlinlib

import mill.api.{Ctx, Result}

trait KotlinWorker {

  def compile(args: String*)(implicit ctx: Ctx): Result[Unit]

}
