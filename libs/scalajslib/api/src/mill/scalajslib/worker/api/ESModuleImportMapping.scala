package mill.scalajslib.worker.api

private[scalajslib] sealed trait ESModuleImportMapping

private[scalajslib] object ESModuleImportMapping {
  case class Prefix(prefix: String, replacement: String) extends ESModuleImportMapping
}
