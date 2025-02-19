package mill.scalalib

import upickle.default.{ReadWriter => RW}

trait JsonFormatters {
  implicit lazy val publicationFormat: RW[coursier.core.Publication] = upickle.default.macroRW
  implicit lazy val extensionFormat: RW[coursier.core.Extension] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val versionConstraintFormat: RW[coursier.version.VersionConstraint] =
    implicitly[RW[String]].bimap(
      _.asString,
      coursier.version.VersionConstraint(_)
    )
  implicit lazy val versionIntervalFormat0: RW[coursier.version.VersionInterval] =
    upickle.default.macroRW
  implicit lazy val versionFormat0: RW[coursier.version.Version] =
    implicitly[RW[String]].bimap(
      _.asString,
      coursier.version.Version(_)
    )
  implicit lazy val variantSelectorFormat: RW[coursier.core.VariantSelector] =
    RW.merge(
      upickle.default.macroRW[coursier.core.VariantSelector.ConfigurationBased]
    )
  implicit lazy val variantFormat: RW[coursier.core.Variant] =
    RW.merge(
      upickle.default.macroRW[coursier.core.Variant.Configuration]
    )
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
  implicit lazy val versionsFormat: RW[coursier.core.Versions] = upickle.default.macroRW
  implicit lazy val versionsDateTimeFormat: RW[coursier.core.Versions.DateTime] =
    upickle.default.macroRW
  implicit lazy val activationFormat: RW[coursier.core.Activation] = upickle.default.macroRW
  implicit lazy val profileFormat: RW[coursier.core.Profile] = upickle.default.macroRW
  implicit lazy val projectFormat: RW[coursier.core.Project] = upickle.default.macroRW

}
object JsonFormatters extends JsonFormatters
