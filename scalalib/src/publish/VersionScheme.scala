package mill.scalalib.publish

sealed abstract class VersionScheme(val value: Option[String])

object VersionScheme {
  case object NoScheme extends VersionScheme(None)
  case object Always extends VersionScheme(Some("always"))
  case object EarlySemVer extends VersionScheme(Some("early-semver"))
  case object PVP extends VersionScheme(Some("pvp"))
  case object SemVerSpec extends VersionScheme(Some("semver-spec"))
  case object Strict extends VersionScheme(Some("strict"))
}
