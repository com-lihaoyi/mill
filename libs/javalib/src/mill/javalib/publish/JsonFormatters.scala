package mill.javalib.publish

import upickle.{ReadWriter => RW}

trait JsonFormatters {
  implicit lazy val artifactFormat: RW[Artifact] = upickle.macroRW
  implicit lazy val developerFormat: RW[Developer] = upickle.macroRW
  implicit lazy val licenseFormat: RW[License] = upickle.readwriter[ujson.Value].bimap(
    v => upickle.writeJs(v)(using upickle.macroRW[License]),
    {
      case ujson.Str(s) => License.knownMap.getOrElse(s, sys.error("Unknown License: " + s))
      case v => upickle.read(v)(using upickle.macroRW[License])
    }
  )
  implicit lazy val versionControlFormat: RW[VersionControl] = upickle.macroRW
  implicit lazy val pomSettingsFormat: RW[PomSettings] = upickle.macroRW
}
object JsonFormatters extends JsonFormatters
