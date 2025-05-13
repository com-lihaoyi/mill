package mill.scalalib.git

import mill.define.PathRef

@mill.api.experimental
trait GitWorker extends AutoCloseable {
  def ratchet(oldRev: String, newRev: Option[String]): Seq[PathRef]
}
