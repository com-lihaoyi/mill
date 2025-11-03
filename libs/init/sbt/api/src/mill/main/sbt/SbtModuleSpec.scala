package mill.main.sbt

import mill.main.buildgen.ModuleSpec
import upickle.default.{ReadWriter, macroRW}

case class SbtModuleSpec(moduleType: SbtModuleType, module: ModuleSpec)
object SbtModuleSpec {
  implicit val rw: ReadWriter[SbtModuleSpec] = macroRW
}
