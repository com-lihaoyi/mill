package mill.scalalib.spotless

import mill.api.Result
import mill.define.{PathRef, TaskCtx}

@mill.api.experimental
trait SpotlessWorker {
  def format(files: Seq[PathRef], check: Boolean)(using TaskCtx): Result[Unit]
}
