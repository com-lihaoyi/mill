package mill.bsp

import mill.api.PathRef

case class BspServerConfig(
    services: Seq[String],
    classpath: Seq[PathRef]
)

object BspServerConfig {
  implicit val jsonify: upickle.default.ReadWriter[BspServerConfig] =
    upickle.default.macroRW
}
