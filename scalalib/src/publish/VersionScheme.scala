package mill.scalalib.publish

sealed abstract class VersionScheme(val value: String) {
  def toProperty: (String, String) = "info.versionScheme" -> value
}

object VersionScheme {

  /**
   * Early Semantic Versioning that would keep binary compatibility
   *  across patch updates within 0.Y.z (for instance 0.13.0 and 0.13.2).
   *  Once it goes 1.0.0, it follows the regular Semantic Versioning where
   *  1.1.0 is bincompat with 1.0.0.
   */
  case object EarlySemVer extends VersionScheme("early-semver")

  /**
   *  Haskell Package Versioning Policy where X.Y are treated as major version
   */
  case object PVP extends VersionScheme("pvp")

  /**
   * Semantic Versioning where all 0.y.z are treated as initial development (no bincompat guarantees)
   */
  case object SemVerSpec extends VersionScheme("semver-spec")

  /**
   * Requires exact match of version
   */
  case object Strict extends VersionScheme("strict")
}
