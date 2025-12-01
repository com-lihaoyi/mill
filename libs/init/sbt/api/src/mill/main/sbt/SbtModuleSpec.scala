package mill.main.sbt

import mill.main.buildgen.ModuleSpec
import upickle.default.{ReadWriter, macroRW, readwriter}

case class SbtModuleSpec(sharedModuleDir: Either[os.SubPath, os.SubPath], module: ModuleSpec)
object SbtModuleSpec {
  implicit val rwSubPath: ReadWriter[os.SubPath] =
    readwriter[String].bimap(_.toString, os.SubPath(_))
  implicit val rw: ReadWriter[SbtModuleSpec] = macroRW
}
