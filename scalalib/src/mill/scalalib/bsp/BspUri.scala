package mill.scalalib.bsp

import mill.api.PathRef

case class BspUri private (uri: String)

object BspUri {
  def apply(uri: String): BspUri = new BspUri(sanitizeUri(uri))
  def apply(path: os.Path): BspUri = apply(path.toNIO.toUri.toString)
  def apply(uri: PathRef): BspUri = apply(uri.path)

  def sanitizeUri(uri: String): String =
    if (uri.endsWith("/")) sanitizeUri(uri.substring(0, uri.length - 1)) else uri
  def sanitizeUri(path: os.Path): String = sanitizeUri(path.toNIO.toUri.toString)
  def sanitizeUri(uri: PathRef): String = sanitizeUri(uri.path)
}
