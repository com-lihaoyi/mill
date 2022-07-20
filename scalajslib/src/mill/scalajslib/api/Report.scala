package mill.scalajslib.api

import upickle.default.{ReadWriter => RW, macroRW}

final class Report private (val publicModules: Iterable[Report.Module], val dest: mill.PathRef) {
  override def toString(): String =
    s"""Report(
       |  publicModules = $publicModules,
       |  dest = $dest
       |)""".stripMargin
}
object Report {
  final class Module private (
      val moduleID: String,
      val jsFileName: String,
      val sourceMapName: Option[String],
      val moduleKind: ModuleKind
  ) {
    override def toString(): String =
      s"""Module(
         |  moduleID = $moduleID,
         |  jsFileName = $jsFileName,
         |  sourceMapName = $sourceMapName,
         |  moduleKind = $moduleKind
         |)""".stripMargin
  }
  object Module {
    def apply(
        moduleID: String,
        jsFileName: String,
        sourceMapName: Option[String],
        moduleKind: ModuleKind
    ): Module =
      new Module(
        moduleID = moduleID,
        jsFileName = jsFileName,
        sourceMapName = sourceMapName,
        moduleKind = moduleKind
      )
    implicit val rw: RW[Module] = macroRW[Module]
  }
  def apply(publicModules: Iterable[Report.Module], dest: mill.PathRef): Report =
    new Report(publicModules = publicModules, dest = dest)
  implicit val rw: RW[Report] = macroRW[Report]
}
