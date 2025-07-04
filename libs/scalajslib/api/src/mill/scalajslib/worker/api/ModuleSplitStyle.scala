package mill.scalajslib.worker.api

private[scalajslib] sealed trait ModuleSplitStyle

private[scalajslib] object ModuleSplitStyle {
  case object FewestModules extends ModuleSplitStyle
  case object SmallestModules extends ModuleSplitStyle
  final case class SmallModulesFor(packages: String*) extends ModuleSplitStyle
}
