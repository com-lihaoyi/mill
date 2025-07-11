package mill.javalib.publish

import upickle.default.{ReadWriter => RW}

trait JsonFormatters {
  implicit lazy val artifactFormat: RW[Artifact] = upickle.default.macroRW
  implicit lazy val developerFormat: RW[Developer] = upickle.default.macroRW
  implicit lazy val licenseFormat: RW[License] = upickle.default.macroRW
  implicit lazy val versionControlFormat: RW[VersionControl] = upickle.default.macroRW
  implicit lazy val pomSettingsFormat: RW[PomSettings] = upickle.default.macroRW
}
object JsonFormatters extends JsonFormatters
