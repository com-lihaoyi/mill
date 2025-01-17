package mill.scalalib

import upickle.default.{ReadWriter => RW}
import mill.api.Mirrors
import mill.api.Mirrors.autoMirror

trait JsonFormatters {
  import JsonFormatters.mirrors.given

  implicit lazy val publicationFormat: RW[coursier.core.Publication] = upickle.default.macroRW
  implicit lazy val extensionFormat: RW[coursier.core.Extension] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val bomDepFormat: RW[coursier.core.BomDependency] = upickle.default.macroRW
  implicit lazy val overridesFormat: RW[coursier.core.Overrides] =
    implicitly[RW[coursier.core.DependencyManagement.Map]].bimap(
      _.flatten.toMap,
      coursier.core.Overrides(_)
    )
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
  implicit lazy val depMgmtKeyFormat: RW[coursier.core.DependencyManagement.Key] =
    upickle.default.macroRW
  implicit lazy val depMgmtValuesFormat: RW[coursier.core.DependencyManagement.Values] =
    upickle.default.macroRW
  implicit lazy val activationOsFormat: RW[coursier.core.Activation.Os] = upickle.default.macroRW
  implicit lazy val infoDeveloperFormat: RW[coursier.core.Info.Developer] = upickle.default.macroRW
  implicit lazy val infoScmFormat: RW[coursier.core.Info.Scm] = upickle.default.macroRW
  implicit lazy val infoLicenseFormat: RW[coursier.core.Info.License] = upickle.default.macroRW
  implicit lazy val infoFormat: RW[coursier.core.Info] = upickle.default.macroRW
  implicit lazy val snapshotVersionFormat: RW[coursier.core.SnapshotVersion] =
    upickle.default.macroRW
  implicit lazy val versionInternalFormat: RW[coursier.core.VersionInterval] =
    upickle.default.macroRW
  implicit lazy val versionFormat: RW[coursier.core.Version] =
    implicitly[RW[String]].bimap(
      _.repr,
      coursier.core.Version(_)
    )
  implicit lazy val snapshotVersioningFormat: RW[coursier.core.SnapshotVersioning] =
    upickle.default.macroRW
  implicit lazy val versionsFormat: RW[coursier.core.Versions] = upickle.default.readwriter[ujson.Value].bimap[coursier.core.Versions](
    versions => ujson.Obj(
      "latest" -> versions.latest,
      "release" -> versions.release,
      "available" -> versions.available,
      "lastUpdated" -> upickle.default.writeJs(versions.lastUpdated)
    ),
    json => coursier.core.Versions(
      latest = json("latest").str,
      release = json("release").str,
      available = upickle.default.read(json("available")),
      lastUpdated = upickle.default.read(json("lastUpdated"))
    )
  )
  implicit lazy val versionsDateTimeFormat: RW[coursier.core.Versions.DateTime] =
    upickle.default.macroRW
  implicit lazy val activationFormat: RW[coursier.core.Activation] = upickle.default.macroRW
  implicit lazy val profileFormat: RW[coursier.core.Profile] = upickle.default.macroRW
  implicit lazy val projectFormat: RW[coursier.core.Project] = upickle.default.macroRW

}
object JsonFormatters extends JsonFormatters {
  private[mill] object mirrors {
    given Root_coursier_Publication: Mirrors.Root[coursier.core.Publication] =
      Mirrors.autoRoot[coursier.core.Publication]
    given Root_coursier_Extension: Mirrors.Root[coursier.core.Extension] =
      Mirrors.autoRoot[coursier.core.Extension]
    given Root_coursier_Module: Mirrors.Root[coursier.core.Module] =
      Mirrors.autoRoot[coursier.core.Module]
    given Root_coursier_BomDependency: Mirrors.Root[coursier.core.BomDependency] =
      Mirrors.autoRoot[coursier.core.BomDependency]
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
    given Root_core_coursier_DependencyManagement_Key
        : Mirrors.Root[coursier.core.DependencyManagement.Key] =
      Mirrors.autoRoot[coursier.core.DependencyManagement.Key]
    given Root_core_coursier_DependencyManagement_Values
        : Mirrors.Root[coursier.core.DependencyManagement.Values] =
      Mirrors.autoRoot[coursier.core.DependencyManagement.Values]
    given Root_coursier_core_Activation_Os
        : Mirrors.Root[coursier.core.Activation.Os] =
      Mirrors.autoRoot[coursier.core.Activation.Os]
    given Root_coursier_core_Info_Developer
        : Mirrors.Root[coursier.core.Info.Developer] =
      Mirrors.autoRoot[coursier.core.Info.Developer]
    given Root_coursier_core_Info_Scm
        : Mirrors.Root[coursier.core.Info.Scm] =
      Mirrors.autoRoot[coursier.core.Info.Scm]
    given Root_coursier_core_Info_License
        : Mirrors.Root[coursier.core.Info.License] =
      Mirrors.autoRoot[coursier.core.Info.License]
    given Root_coursier_core_Info
        : Mirrors.Root[coursier.core.Info] =
      Mirrors.autoRoot[coursier.core.Info]
    given Root_coursier_core_SnapshotVersion
        : Mirrors.Root[coursier.core.SnapshotVersion] =
      Mirrors.autoRoot[coursier.core.SnapshotVersion]
    given Root_coursier_core_VersionInterval
        : Mirrors.Root[coursier.core.VersionInterval] =
      Mirrors.autoRoot[coursier.core.VersionInterval]
    given Root_coursier_core_SnapshotVersioning
        : Mirrors.Root[coursier.core.SnapshotVersioning] =
      Mirrors.autoRoot[coursier.core.SnapshotVersioning]
    given Root_coursier_core_Versions
        : Mirrors.Root[coursier.core.Versions] =
      Mirrors.autoRoot[coursier.core.Versions]
    given Root_coursier_core_Versions_DateTime
        : Mirrors.Root[coursier.core.Versions.DateTime] =
      Mirrors.autoRoot[coursier.core.Versions.DateTime]
    given Root_coursier_core_Activation
        : Mirrors.Root[coursier.core.Activation] =
      Mirrors.autoRoot[coursier.core.Activation]
    given Root_coursier_core_Profile
        : Mirrors.Root[coursier.core.Profile] =
      Mirrors.autoRoot[coursier.core.Profile]
    given Root_coursier_core_Project
        : Mirrors.Root[coursier.core.Project] =
      Mirrors.autoRoot[coursier.core.Project]
  }
}
