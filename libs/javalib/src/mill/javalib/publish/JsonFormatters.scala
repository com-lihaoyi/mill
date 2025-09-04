package mill.javalib.publish

import upickle.{ReadWriter => RW}

trait JsonFormatters {
  implicit lazy val artifactFormat: RW[Artifact] = upickle.macroRW
  implicit lazy val developerFormat: RW[Developer] = upickle.macroRW
  implicit lazy val licenseFormat: RW[License] = upickle.macroRW
  implicit lazy val versionControlFormat: RW[VersionControl] = upickle.macroRW
  implicit lazy val pomSettingsFormat: RW[PomSettings] = upickle.macroRW
}
object JsonFormatters extends JsonFormatters
