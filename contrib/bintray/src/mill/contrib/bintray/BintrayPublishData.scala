package mill.contrib.bintray

import mill.define.PathRef
import mill.scalalib.publish.Artifact

case class BintrayPublishData(
    meta: Artifact,
    payload: Seq[(PathRef, String)],
    bintrayPackage: String
)

object BintrayPublishData {
  implicit def jsonify: upickle.default.ReadWriter[BintrayPublishData] = upickle.default.macroRW
}
