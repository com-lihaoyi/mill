package mill.api

import mill.api.BspUri

case class BspUri(uri: String)

object BspUri {
  def apply(path: java.nio.file.Path): BspUri = BspUri(path.toUri.toString)
}
