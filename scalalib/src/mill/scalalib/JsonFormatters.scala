package mill.scalalib

import upickle.default.{ReadWriter => RW}
import mill.api.Mirrors
import mill.api.Mirrors.autoMirror

trait JsonFormatters {
  import JsonFormatters.mirrors.given

  implicit lazy val publicationFormat: RW[coursier.core.Publication] = upickle.default.macroRW
  implicit lazy val extensionFormat: RW[coursier.core.Extension] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.core.Dependency] = upickle.default.macroRW
  implicit lazy val minimizedExclusionsFormat: RW[coursier.core.MinimizedExclusions] =
    upickle.default.macroRW
  implicit lazy val exclusionDataFormat: RW[coursier.core.MinimizedExclusions.ExclusionData] =
    RW.merge(
      upickle.default.macroRW[coursier.core.MinimizedExclusions.ExcludeNone.type],
      upickle.default.macroRW[coursier.core.MinimizedExclusions.ExcludeAll.type],
      upickle.default.macroRW[coursier.core.MinimizedExclusions.ExcludeSpecific]
    )
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
  implicit lazy val orgFormat: RW[coursier.Organization] = upickle.default.macroRW
  implicit lazy val modNameFormat: RW[coursier.ModuleName] = upickle.default.macroRW
  implicit lazy val configurationFormat: RW[coursier.core.Configuration] = upickle.default.macroRW
  implicit lazy val typeFormat: RW[coursier.core.Type] = upickle.default.macroRW
  implicit lazy val classifierFormat: RW[coursier.core.Classifier] = upickle.default.macroRW

}
object JsonFormatters extends JsonFormatters {
  private[JsonFormatters] object mirrors {
    given Root_coursier_Publication: Mirrors.Root[coursier.core.Publication] =
      Mirrors.autoRoot[coursier.core.Publication]
    given Root_coursier_Extension: Mirrors.Root[coursier.core.Extension] =
      Mirrors.autoRoot[coursier.core.Extension]
    given Root_coursier_Module: Mirrors.Root[coursier.core.Module] =
      Mirrors.autoRoot[coursier.core.Module]
    given Root_coursier_Dependency: Mirrors.Root[coursier.core.Dependency] =
      Mirrors.autoRoot[coursier.core.Dependency]
    given Root_coursier_MinimizedExclusions: Mirrors.Root[coursier.core.MinimizedExclusions] =
      Mirrors.autoRoot[coursier.core.MinimizedExclusions]
    given Root_coursier_MinimizedExclusions_ExcludeSpecific
        : Mirrors.Root[coursier.core.MinimizedExclusions.ExcludeSpecific] =
      Mirrors.autoRoot[coursier.core.MinimizedExclusions.ExcludeSpecific]
    given Root_coursier_core_Attributes: Mirrors.Root[coursier.core.Attributes] =
      Mirrors.autoRoot[coursier.core.Attributes]
    given Root_coursier_Organization: Mirrors.Root[coursier.core.Organization] =
      Mirrors.autoRoot[coursier.core.Organization]
    given Root_coursier_ModuleName: Mirrors.Root[coursier.core.ModuleName] =
      Mirrors.autoRoot[coursier.core.ModuleName]
    given Root_coursier_Configuration: Mirrors.Root[coursier.core.Configuration] =
      Mirrors.autoRoot[coursier.core.Configuration]
    given Root_coursier_Type: Mirrors.Root[coursier.core.Type] =
      Mirrors.autoRoot[coursier.core.Type]
    given Root_coursier_Classifier: Mirrors.Root[coursier.core.Classifier] =
      Mirrors.autoRoot[coursier.core.Classifier]
  }
}
