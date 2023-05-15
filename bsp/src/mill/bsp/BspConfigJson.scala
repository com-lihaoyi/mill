package mill.bsp

import upickle.default._

private case class BspConfigJson(
    name: String,
    argv: Seq[String],
    millVersion: String,
    bspVersion: String,
    languages: Seq[String]
)

private object BspConfigJson {
  implicit val rw: ReadWriter[BspConfigJson] = macroRW
}
