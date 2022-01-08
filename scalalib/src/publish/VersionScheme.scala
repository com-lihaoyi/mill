package mill.scalalib.publish

sealed abstract class VersionScheme(val value: String)

object VersionScheme {
  case object Always extends VersionScheme("always")
  case object EarlySemVer extends VersionScheme("early-semver")
  case object PVP extends VersionScheme("pvp")
  case object SemVerSpec extends VersionScheme("semver-spec")
  case object Strict extends VersionScheme("strict")
}
