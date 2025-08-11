package mill.androidlib

import mill.api.JsonFormatters.relPathRW
import mill.api.PathRef

case class AndroidPackageableExtraFile(source: PathRef, destination: os.RelPath)

object AndroidPackageableExtraFile {
  implicit val resultRW: upickle.default.ReadWriter[AndroidPackageableExtraFile] =
    upickle.default.macroRW
}
