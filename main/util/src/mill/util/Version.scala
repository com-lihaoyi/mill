package mill.util

import mill.api.experimental

@experimental
class Version private (
    val major: Int,
    val minor: Option[Int],
    val micro: Option[Int],
    val qualifierWithSep: Option[(String, String)]
) { outer =>

  override def toString(): String = Seq(
    Some(major),
    minor.map("." + _),
    micro.map("." + _),
    qualifierWithSep.map(q => q._2 + q._1)
  ).flatten.mkString

  def isNewerThan(other: Version)(implicit ordering: Ordering[Version]): Boolean =
    ordering.compare(this, other) > 0

  def chooseNewest(versions: Version*)(implicit ordering: Ordering[Version]): Version =
    versions.foldLeft(this)((best, next) =>
      if (next.isNewerThan(best)) next else best
    )

  def isAtLeast(other: Version)(implicit ordering: Ordering[Version]): Boolean =
    ordering.compare(this, other) >= 0

  def asMaven: MavenVersion = new MavenVersion(this)
  def asOsgiVersion: OsgiVersion = new OsgiVersion(this)
  def asIgnoreQualifierVersion: IgnoreQualifierVersion = new IgnoreQualifierVersion(this)
}

final class MavenVersion(val underlying: Version) extends AnyVal {
  def isNewerThan(other: MavenVersion): Boolean = {
    underlying.isNewerThan(other.underlying)(Version.MavenOrdering)
  }
}
final class OsgiVersion(val underlying: Version) extends AnyVal {
  def isNewerThan(other: OsgiVersion): Boolean =
    underlying.isNewerThan(other.underlying)(Version.OsgiOrdering)
}
final class IgnoreQualifierVersion(val underlying: Version) extends AnyVal {
  def isNewerThan(other: IgnoreQualifierVersion): Boolean =
    underlying.isNewerThan(other.underlying)(Version.IgnoreQualifierOrdering)
}

@experimental
object Version {

  /**
   * Missing minor or micro versions are equal to zero,
   * a qualifier is ignored,
   * stable ordering.
   */
  object IgnoreQualifierOrdering extends Ordering[Version] {
    override def compare(l: Version, r: Version): Int =
      l.major - r.major match {
        case 0 =>
          l.minor.getOrElse(0) - r.minor.getOrElse(0) match {
            case 0 => l.micro.getOrElse(0) - r.micro.getOrElse(0)
            case x => x
          }
        case x => x
      }
  }

  /**
   * Missing minor or micro versions are equal to zero,
   * a qualifier is higher that no qualifier,
   * qualifiers are sorted alphabetically,
   * stable ordering.
   */
  object OsgiOrdering extends Ordering[Version] {
    override def compare(l: Version, r: Version): Int =
      l.major - r.major match {
        case 0 =>
          l.minor.getOrElse(0) - r.minor.getOrElse(0) match {
            case 0 =>
              l.micro.getOrElse(0) - r.micro.getOrElse(0) match {
                case 0 =>
                  l.qualifierWithSep.map(_._1).getOrElse("")
                    .compareTo(r.qualifierWithSep.map(_._1).getOrElse(""))
                case x => x
              }
            case x => x
          }
        case x => x
      }
  }

  /**
   * Missing minor or micro versions are equal to zero,
   * a qualifier is lower that no qualifier,
   * qualifiers are sorted alphabetically,
   * stable ordering.
   *
   * TODO: Review ordering wrt Maven 3
   * TODO: also consider a coursier ordering
   */
  @experimental
  object MavenOrdering extends Ordering[Version] {
    override def compare(l: Version, r: Version): Int =
      l.major - r.major match {
        case 0 =>
          l.minor.getOrElse(0) - r.minor.getOrElse(0) match {
            case 0 =>
              l.micro.getOrElse(0) - r.micro.getOrElse(0) match {
                case 0 =>
                  (l.qualifierWithSep.isDefined, r.qualifierWithSep.isDefined) match {
                    case (false, false) => 0
                    case (true, false) => -1
                    case (false, true) => 1
                    case (true, true) =>
                      l.qualifierWithSep.map(_._1).getOrElse("")
                        .compareTo(r.qualifierWithSep.map(_._1).getOrElse(""))
                  }
                case x => x
              }
            case x => x
          }
        case x => x
      }
  }

  private val Pattern = raw"""(\d+)(\.(\d+)(\.(\d+))?)?(([._\-+])(.+))?""".r

  def parse(version: String): Version = {
    version match {
      case Pattern(major, _, minor, _, micro, _, qualifierSep, qualifier) =>
        new Version(
          major.toInt,
          Option(minor).map(_.toInt),
          Option(micro).map(_.toInt),
          Option(qualifier).map(q => (q, qualifierSep))
        )
    }
  }

  def chooseNewest(
      version: String,
      versions: String*
  )(implicit
      ordering: Ordering[Version]
  ): String =
    chooseNewest(parse(version), versions.map(parse)*).toString()

  def chooseNewest(
      version: Version,
      versions: Version*
  )(implicit
      ordering: Ordering[Version]
  ): Version =
    versions.foldLeft(version)((best, next) =>
      if (next.isNewerThan(best)) next else best
    )

  def isAtLeast(version: String, atLeast: String)(implicit ordering: Ordering[Version]): Boolean =
    Version.parse(version).isAtLeast(Version.parse(atLeast))

}
