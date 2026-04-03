package mill.main.sbt

import mill.main.buildgen._
import upickle.default.{ReadWriter, macroRW, readwriter}

case class SbtModuleSpec(root: Either[os.SubPath, PackageSpec], module: ModuleSpec)
object SbtModuleSpec {
  implicit val rwSubPath: ReadWriter[os.SubPath] =
    readwriter[String].bimap(_.toString, os.SubPath(_))
  implicit val rw: ReadWriter[SbtModuleSpec] = macroRW
}
