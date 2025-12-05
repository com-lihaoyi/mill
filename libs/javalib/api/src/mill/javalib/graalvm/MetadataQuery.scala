package mill.javalib.graalvm

import mill.api.JsonFormatters.pathReadWrite

case class MetadataQuery(
    rootPath: os.Path,
    deps: Set[String],
    useLatestConfigWhenVersionIsUntested: Boolean
)

object MetadataQuery {
  implicit val rw: upickle.ReadWriter[MetadataQuery] = upickle.macroRW
}
