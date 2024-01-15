package mill.bsp

import mill.api.PathRef

case class BspServerConfig(
    services: Seq[String],
    classpath: Seq[PathRef],
    languages: Seq[String]
)

object BspServerConfig {
  implicit val jsonify: upickle.default.ReadWriter[BspServerConfig] =
    upickle.default.macroRW
}
