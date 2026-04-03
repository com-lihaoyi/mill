package mill.javalib.publish

import upickle.{ReadWriter => RW}

trait JsonFormatters {
  implicit lazy val artifactFormat: RW[Artifact] = upickle.macroRW
  implicit lazy val developerFormat: RW[Developer] = upickle.macroRW
  implicit lazy val licenseFormat: RW[License] = upickle.readwriter[ujson.Value].bimap(
    v => upickle.writeJs(v)(using upickle.macroRW[License]),
    {
      case ujson.Str(s) =>
        // Try to look up by SPDX ID first, then by name, then create from name as fallback
        License.knownMap.get(s)
          .orElse(License.knownMap.values.find(_.name == s))
          .getOrElse(License(name = s, url = ""))

      case v => upickle.read(v)(using upickle.macroRW[License])
    }
  )
  implicit lazy val versionControlFormat: RW[VersionControl] =
    upickle.readwriter[ujson.Value].bimap(
      v => upickle.writeJs(v)(using upickle.macroRW[VersionControl]),
      {
        case ujson.Str(s) => VersionControl(browsableRepository = Some(s))
        case v => upickle.read(v)(using upickle.macroRW[VersionControl])
      }
    )
  implicit lazy val pomSettingsFormat: RW[PomSettings] = upickle.macroRW
}
object JsonFormatters extends JsonFormatters
