package mill.javalib.publish
import upickle.{macroRW, ReadWriter}
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
  implicit val rwEarlySemVer: ReadWriter[EarlySemVer.type] = macroRW

  /**
   *  Haskell Package Versioning Policy where X.Y are treated as major version
   */
  case object PVP extends VersionScheme("pvp")
  implicit val rwPVP: ReadWriter[PVP.type] = macroRW

  /**
   * Semantic Versioning where all 0.y.z are treated as initial development (no bincompat guarantees)
   */
  case object SemVerSpec extends VersionScheme("semver-spec")
  implicit val rwSemVerSpec: ReadWriter[SemVerSpec.type] = macroRW

  /**
   * Requires exact match of version
   */
  case object Strict extends VersionScheme("strict")
  implicit val rwStrict: ReadWriter[Strict.type] = macroRW

  // edit @bishabosha: why was it `.type`, I assume it is meant to infer a sum type?
  implicit val rwVersionScheme: ReadWriter[VersionScheme /*.type*/ ] = macroRW
}
