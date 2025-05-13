package mill.scalalib.spotless

import mill.api.Result
import mill.define.{PathRef, TaskCtx}

@mill.api.experimental
trait SpotlessWorker {

  /**
   * Checks/fixes formatting in `files`.
   */
  def format(files: Seq[PathRef], check: Boolean)(using TaskCtx): Result[Unit]

  /**
   * Resolves and returns any artifacts needed by [[format]].
   */
  def provision(using TaskCtx): Seq[PathRef]
}
