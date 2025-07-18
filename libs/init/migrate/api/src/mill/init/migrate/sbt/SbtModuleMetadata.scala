package mill.init.migrate
package sbt

import upickle.default.{ReadWriter, macroRW}

case class SbtModuleMetadata(
    crossScalaVersions: Seq[String],
    crossPlatformBaseDir: Option[Seq[String]],
    testModule: Option[MetaModule[Unit]]
)
object SbtModuleMetadata {
  implicit val rw: ReadWriter[SbtModuleMetadata] = macroRW
}
