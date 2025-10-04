package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * A representation, intended for code generation, for a test module.
 */
case class TestModuleRepr(
    name: String = "test",
    supertypes: Seq[String] = Nil,
    mixins: Seq[String] = Nil,
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil,
    testParallelism: Boolean = true,
    testSandboxWorkingDir: Boolean = true
)
object TestModuleRepr {
  implicit val rw: ReadWriter[TestModuleRepr] = macroRW
}
