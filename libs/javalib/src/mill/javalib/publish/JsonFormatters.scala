package mill.javalib.publish

import upickle.{ReadWriter => RW}

trait JsonFormatters {
  implicit lazy val artifactFormat: RW[Artifact] = upickle.macroRW
  implicit lazy val developerFormat: RW[Developer] = upickle.macroRW
  implicit lazy val licenseFormat: RW[License] = upickle.readwriter[ujson.Value].bimap(
    v => upickle.writeJs(v)(using upickle.macroRW[License]),
    {
      case ujson.Str(s) =>
        License.knownMap.getOrElse(
          s,
          sys.error(
            s"Unknown License: `$s`, needs to be one of " +
              License.knownMap.keys.mkString(", ")
          )
        )
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
