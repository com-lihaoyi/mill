package mill.scalajslib.api

import mill.api.internal.Mirrors
import upickle.{ReadWriter => RW, macroRW}
import Mirrors.autoMirror

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
      Module(
        moduleID = moduleID,
        jsFileName = jsFileName,
        sourceMapName = sourceMapName,
        moduleKind = moduleKind
      )
    implicit val rw: RW[Module] = macroRW[Module]

    private given Root_Module: Mirrors.Root[Module] =
      Mirrors.autoRoot[Module]
  }
  def apply(publicModules: Iterable[Report.Module], dest: mill.PathRef): Report =
    Report(publicModules = publicModules, dest = dest)
  implicit val rw: RW[Report] = macroRW[Report]

  private given Root_Module: Mirrors.Root[Report] =
    Mirrors.autoRoot[Report]
}
