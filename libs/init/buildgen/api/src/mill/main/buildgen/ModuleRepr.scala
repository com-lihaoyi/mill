package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * A representation for a module in a build that is optimized for code generation.
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
