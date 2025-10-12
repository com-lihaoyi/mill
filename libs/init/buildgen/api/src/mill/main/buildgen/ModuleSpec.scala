package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * Specification for encoding a Mill build module as a Scala type.
 * @param name Name of this type.
 * @param supertypes Names of Scala types that this type extends.
 * @param mixins Names of Scala mixin supertypes.
 * @param configs Data for configuring this instance.
 * @param crossConfigs Data for configuring this instance that varies by cross-value.
 *                     - Cross support is limited to a single `String` value.
 *                     - This is empty for non-cross modules.
 */
case class ModuleSpec(
    name: String,
    // TODO Can this be auto-derived from configs?
    supertypes: Seq[String] = Nil,
    mixins: Seq[String] = Nil,
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil,
    nestedModules: Seq[ModuleSpec] = Nil
) {
  def isPublishModule = configs.exists(_.isInstanceOf[ModuleConfig.PublishModule])
  def isScalaModule = configs.exists(_.isInstanceOf[ModuleConfig.ScalaModule])
  def isTestModule = configs.exists(_.isInstanceOf[ModuleConfig.TestModule])

  def sequence: Seq[ModuleSpec] = this +: nestedModules.flatMap(_.sequence)
  def transform(f: ModuleSpec => ModuleSpec): ModuleSpec =
    f(copy(nestedModules = nestedModules.map(_.transform(f))))
}
object ModuleSpec {
  implicit val rw: ReadWriter[ModuleSpec] = macroRW
}
