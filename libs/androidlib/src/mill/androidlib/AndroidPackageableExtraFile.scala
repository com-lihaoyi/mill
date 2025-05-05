package mill.androidlib

import mill.define.JsonFormatters.relPathRW
import mill.define.PathRef

case class AndroidPackageableExtraFile(source: PathRef, destination: os.RelPath)

object AndroidPackageableExtraFile {
  implicit val resultRW: upickle.default.ReadWriter[AndroidPackageableExtraFile] =
    upickle.default.macroRW
}
