package mill.scalalib.bsp

case class BspUri(uri: String)

object BspUri {
  def apply(path: os.Path): BspUri = BspUri(path.toNIO.toUri.toString)
}
