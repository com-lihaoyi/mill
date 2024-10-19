package mill.scalalib

import upickle.default.{ReadWriter => RW}

trait JsonFormatters {
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
  implicit lazy val classifierFormat: RW[coursier.core.Classifier] = upickle.default.macroRW
  implicit lazy val coursierTypeRW: RW[coursier.core.Type] =
    upickle.default.readwriter[String].bimap(
      _.value,
      coursier.core.Type(_)
    )
  implicit lazy val coursierTypeSetRW: RW[Set[coursier.core.Type]] =
    upickle.default.readwriter[Set[String]].bimap(
      _.map(_.value),
      _.map(coursier.core.Type(_))
    )

}
object JsonFormatters extends JsonFormatters
