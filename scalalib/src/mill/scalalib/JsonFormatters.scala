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
      upickle.default.macroRW[coursier.core.VariantSelector.ConfigurationBased],
      upickle.default.macroRW[coursier.core.VariantSelector.AttributesBased]
    )
  private implicit lazy val variantAttributesFormat: RW[coursier.core.Variant.Attributes] =
    upickle.default.macroRW
  implicit lazy val variantFormat: RW[coursier.core.Variant] =
    RW.merge(
      upickle.default.macroRW[coursier.core.Variant.Configuration],
      variantAttributesFormat
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
  implicit lazy val versionsFormat: RW[coursier.core.Versions] =
    upickle.default.readwriter[ujson.Value].bimap[coursier.core.Versions](
      versions =>
        ujson.Obj(
          "latest" -> versions.latest,
          "release" -> versions.release,
          "available" -> versions.available,
          "lastUpdated" -> upickle.default.writeJs(versions.lastUpdated)
        ),
      json =>
        coursier.core.Versions(
          latest = json("latest").str,
          release = json("release").str,
          available = upickle.default.read[List[String]](json("available")),
          lastUpdated =
            upickle.default.read[Option[coursier.core.Versions.DateTime]](json("lastUpdated"))
        )
    )
  implicit lazy val versionsDateTimeFormat: RW[coursier.core.Versions.DateTime] =
    upickle.default.macroRW
  implicit lazy val activationFormat: RW[coursier.core.Activation] = upickle.default.macroRW
  implicit lazy val profileFormat: RW[coursier.core.Profile] = upickle.default.macroRW
  private implicit lazy val variantPublicationFormat: RW[coursier.core.VariantPublication] =
    upickle.default.macroRW
  private implicit def attributesMapFormat[T: RW]: RW[Map[coursier.core.Variant.Attributes, T]] =
    implicitly[RW[Map[String, T]]].bimap(
      attrMap => attrMap.map { case (k, v) => k.variantName -> v },
      strMap => strMap.map { case (k, v) => coursier.core.Variant.Attributes(k) -> v }
    )
  implicit lazy val projectFormat: RW[coursier.core.Project] = upickle.default.macroRW

}
object JsonFormatters extends JsonFormatters
