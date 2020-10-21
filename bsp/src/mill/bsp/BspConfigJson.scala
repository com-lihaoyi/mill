package mill.bsp

import ch.epfl.scala.bsp4j.BspConnectionDetails
import scala.jdk.CollectionConverters._
import upickle.default._

case class BspConfigJson(
    name: String,
    argv: Seq[String],
    millVersion: String,
    bspVersion: String,
    languages: Seq[String]
) extends BspConnectionDetails(name, argv.asJava, millVersion, bspVersion, languages.asJava)

object BspConfigJson {
  implicit val rw: ReadWriter[BspConfigJson] = macroRW
}
