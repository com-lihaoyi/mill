package mill.javalib

import upickle.{ReadWriter => RW}
import mill.api.internal.Mirrors.autoMirror
import mill.api.daemon.internal.TestReporter
import mill.api.internal.Mirrors

trait JsonFormatters {
  import JsonFormatters.mirrors.given

  implicit lazy val publicationFormat: RW[coursier.core.Publication] = upickle.macroRW
  implicit lazy val extensionFormat: RW[coursier.core.Extension] = upickle.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.macroRW
  implicit lazy val versionConstraintFormat: RW[coursier.version.VersionConstraint] =
    summon[RW[String]].bimap(
      _.asString,
      coursier.version.VersionConstraint(_)
    )
  implicit lazy val versionIntervalFormat0: RW[coursier.version.VersionInterval] =
    upickle.macroRW
  implicit lazy val versionFormat0: RW[coursier.version.Version] =
    summon[RW[String]].bimap(
      _.asString,
      coursier.version.Version(_)
    )
  implicit lazy val variantMatcherFormat: RW[coursier.core.VariantSelector.VariantMatcher] =
    RW.merge(
      upickle.macroRW[coursier.core.VariantSelector.VariantMatcher.Api.type],
      upickle.macroRW[coursier.core.VariantSelector.VariantMatcher.Runtime.type],
      upickle.macroRW[coursier.core.VariantSelector.VariantMatcher.Equals],
      upickle.macroRW[coursier.core.VariantSelector.VariantMatcher.MinimumVersion],
      upickle.macroRW[coursier.core.VariantSelector.VariantMatcher.AnyOf],
      upickle.macroRW[coursier.core.VariantSelector.VariantMatcher.EndsWith]
    )
  implicit lazy val variantSelectorFormat: RW[coursier.core.VariantSelector] =
    RW.merge(
      upickle.macroRW[coursier.core.VariantSelector.ConfigurationBased],
      upickle.macroRW[coursier.core.VariantSelector.AttributesBased]
    )
  private implicit lazy val variantAttributesFormat: RW[coursier.core.Variant.Attributes] =
    upickle.macroRW
  implicit lazy val variantFormat: RW[coursier.core.Variant] =
    RW.merge(
      upickle.macroRW[coursier.core.Variant.Configuration],
      variantAttributesFormat
    )
  implicit lazy val bomDepFormat: RW[coursier.core.BomDependency] = upickle.macroRW
  implicit lazy val overridesFormat: RW[coursier.core.Overrides] =
    summon[RW[coursier.core.DependencyManagement.Map]].bimap(
      _.flatten.toMap,
      coursier.core.Overrides(_)
    )
  implicit lazy val depFormat: RW[coursier.core.Dependency] = upickle.macroRW
  implicit lazy val minimizedExclusionsFormat: RW[coursier.core.MinimizedExclusions] =
    upickle.macroRW
  implicit lazy val exclusionDataFormat: RW[coursier.core.MinimizedExclusions.ExclusionData] =
    RW.merge(
      upickle.macroRW[coursier.core.MinimizedExclusions.ExcludeNone.type],
      upickle.macroRW[coursier.core.MinimizedExclusions.ExcludeAll.type],
      upickle.macroRW[coursier.core.MinimizedExclusions.ExcludeSpecific]
    )
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.macroRW
  implicit lazy val orgFormat: RW[coursier.Organization] = upickle.macroRW
  implicit lazy val modNameFormat: RW[coursier.ModuleName] = upickle.macroRW
  implicit lazy val configurationFormat: RW[coursier.core.Configuration] = upickle.macroRW
  implicit lazy val typeFormat: RW[coursier.core.Type] = upickle.macroRW
  implicit lazy val classifierFormat: RW[coursier.core.Classifier] = upickle.macroRW
  implicit lazy val depMgmtKeyFormat: RW[coursier.core.DependencyManagement.Key] =
    upickle.macroRW
  implicit lazy val depMgmtValuesFormat: RW[coursier.core.DependencyManagement.Values] =
    upickle.macroRW
  implicit lazy val activationOsFormat: RW[coursier.core.Activation.Os] = upickle.macroRW
  implicit lazy val infoDeveloperFormat: RW[coursier.core.Info.Developer] = upickle.macroRW
  implicit lazy val infoScmFormat: RW[coursier.core.Info.Scm] = upickle.macroRW
  implicit lazy val infoLicenseFormat: RW[coursier.core.Info.License] = upickle.macroRW
  implicit lazy val infoFormat: RW[coursier.core.Info] = upickle.macroRW
  implicit lazy val snapshotVersionFormat: RW[coursier.core.SnapshotVersion] =
    upickle.macroRW
  implicit lazy val versionInternalFormat: RW[coursier.core.VersionInterval] =
    upickle.macroRW
  implicit lazy val versionFormat: RW[coursier.core.Version] =
    summon[RW[String]].bimap(
      _.repr,
      coursier.core.Version(_)
    )
  implicit lazy val snapshotVersioningFormat: RW[coursier.core.SnapshotVersioning] =
    upickle.macroRW
  implicit lazy val versionsFormat: RW[coursier.core.Versions] =
    upickle.readwriter[ujson.Value].bimap[coursier.core.Versions](
      versions =>
        ujson.Obj(
          "latest" -> versions.latest,
          "release" -> versions.release,
          "available" -> versions.available,
          "lastUpdated" -> upickle.writeJs(versions.lastUpdated)
        ),
      json =>
        coursier.core.Versions(
          latest = json("latest").str,
          release = json("release").str,
          available = upickle.read(json("available")): List[String],
          lastUpdated =
            upickle.read(json("lastUpdated")): Option[coursier.core.Versions.DateTime]
        )
    )
  implicit lazy val versionsDateTimeFormat: RW[coursier.core.Versions.DateTime] =
    upickle.macroRW
  implicit lazy val activationFormat: RW[coursier.core.Activation] = upickle.macroRW
  implicit lazy val profileFormat: RW[coursier.core.Profile] = upickle.macroRW
  private implicit lazy val variantPublicationFormat: RW[coursier.core.VariantPublication] =
    upickle.macroRW
  private implicit def attributesMapFormat[T: RW]: RW[Map[coursier.core.Variant.Attributes, T]] =
    summon[RW[Map[String, T]]].bimap(
      attrMap => attrMap.map { case (k, v) => k.variantName -> v },
      strMap => strMap.map { case (k, v) => coursier.core.Variant.Attributes(k) -> v }
    )
  implicit lazy val projectFormat: RW[coursier.core.Project] = upickle.macroRW

  implicit lazy val logLevelRW: upickle.ReadWriter[TestReporter.LogLevel] =
    summon[upickle.ReadWriter[String]].bimap(
      _.asString,
      TestReporter.LogLevel.fromString(_)
    )
}

/**
 * JSON read/writing codecs for most common external data types
 */
object JsonFormatters extends JsonFormatters {
  private[mill] object mirrors {
    given Root_coursier_Extension: Mirrors.Root[coursier.core.Extension] =
      Mirrors.autoRoot[coursier.core.Extension]
    given Root_coursier_version_VersionInterval: Mirrors.Root[coursier.version.VersionInterval] =
      Mirrors.autoRoot[coursier.version.VersionInterval]
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
