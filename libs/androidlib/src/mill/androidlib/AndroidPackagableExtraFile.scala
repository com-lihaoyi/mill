package mill.androidlib

import mill.define.JsonFormatters.relPathRW
import mill.define.PathRef

case class AndroidPackagableExtraFile(source: PathRef, destination: os.RelPath)

object AndroidPackagableExtraFile {
  implicit val resultRW: upickle.default.ReadWriter[AndroidPackagableExtraFile] =
    upickle.default.macroRW
}
