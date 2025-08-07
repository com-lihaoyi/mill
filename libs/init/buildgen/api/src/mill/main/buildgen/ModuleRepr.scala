package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

case class ModuleRepr(
    segments: Seq[String],
    supertypes: Seq[String] = Seq("Module"),
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil,
    testModule: Option[TestModuleRepr] = None
)
object ModuleRepr {
  implicit val rw: ReadWriter[ModuleRepr] = macroRW
}
