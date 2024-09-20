package mill.scalajslib.api

import upickle.default.{ReadWriter => RW, macroRW}
import scala.deriving.Mirror

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

    // GENERATED CODE BY ci/scripts/manual_mirror_gen.sc - DO NOT EDIT
    private given Mirror_Module: Mirror.Product with {
      final type MirroredMonoType = Module
      final type MirroredType = Module
      final type MirroredElemTypes = (String, String, Option[String], ModuleKind)
      final type MirroredElemLabels = ("moduleID", "jsFileName", "sourceMapName", "moduleKind")

      final def fromProduct(p: scala.Product): Module = {
        val _1: String = p.productElement(0).asInstanceOf[String]
        val _2: String = p.productElement(1).asInstanceOf[String]
        val _3: Option[String] = p.productElement(2).asInstanceOf[Option[String]]
        val _4: ModuleKind = p.productElement(3).asInstanceOf[ModuleKind]

        Module.apply(_1, _2, _3, _4)
      }
    }
  }
  def apply(publicModules: Iterable[Report.Module], dest: mill.PathRef): Report =
    new Report(publicModules = publicModules, dest = dest)
  implicit val rw: RW[Report] = macroRW[Report]

  // GENERATED CODE BY ci/scripts/manual_mirror_gen.sc - DO NOT EDIT
  private given Mirror_Report: Mirror.Product with {
    final type MirroredMonoType = Report
    final type MirroredType = Report
    final type MirroredElemTypes = (Iterable[Report.Module], mill.PathRef)
    final type MirroredElemLabels = ("publicModules", "dest")

    final def fromProduct(p: scala.Product): Report = {
      val _1: Iterable[Report.Module] = p.productElement(0).asInstanceOf[Iterable[Report.Module]]
      val _2: mill.PathRef = p.productElement(1).asInstanceOf[mill.PathRef]

      Report.apply(_1, _2)
    }
  }
}
