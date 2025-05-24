package mill.scalalib.git

import mill.define.{PathRef, TaskCtx}

@mill.api.experimental
trait GitWorker {
  def ratchet(oldRev: String, newRev: Option[String])(using TaskCtx): Seq[PathRef]
}
