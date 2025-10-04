package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * A representation, intended for code generation, for a build module.
 */
case class ModuleRepr(
    segments: Seq[String],
    supertypes: Seq[String] = Seq("Module"),
    mixins: Seq[String] = Nil,
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil,
    testModule: Option[TestModuleRepr] = None
)
object ModuleRepr {
  implicit val rw: ReadWriter[ModuleRepr] = macroRW
}
