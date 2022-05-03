package mill.scalajslib.api

import upickle.default.{ReadWriter => RW, macroRW}

final class Report private (val publicModules: Iterable[Report.Module]) {
  override def toString(): String =
    s"""Report(
       |  publicModules = $publicModules
       |)""".stripMargin
}
object Report {
  final class Module private (
      val moduleID: String,
      val jsFile: mill.PathRef,
      val sourceMapName: Option[String],
      val moduleKind: ModuleKind
  ) {
    override def toString(): String =
      s"""Module(
         |  moduleID = $moduleID,
         |  jsFile = $jsFile,
         |  sourceMapName = $sourceMapName,
         |  moduleKind = $moduleKind
         |)""".stripMargin
  }
  object Module {
    def apply(
        moduleID: String,
        jsFile: mill.PathRef,
        sourceMapName: Option[String],
        moduleKind: ModuleKind
    ): Module =
      new Module(
        moduleID = moduleID,
        jsFile = jsFile,
        sourceMapName = sourceMapName,
        moduleKind = moduleKind
      )
    implicit val rw: RW[Module] = macroRW[Module]
  }
  def apply(publicModules: Iterable[Report.Module]): Report =
    new Report(publicModules = publicModules)
  implicit val rw: RW[Report] = macroRW[Report]
}
