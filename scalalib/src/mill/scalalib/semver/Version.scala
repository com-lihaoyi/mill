package mill.scalalib.semver

import scala.util.matching.Regex

sealed trait Version {

  def major: BigInt
  def minor: BigInt
  def patch: BigInt

  def baseVersion: ReleaseVersion = ReleaseVersion(major, minor, patch)

}

object Version {
  val VersionPattern: Regex = """(\d+)\.(\d+)\.(\d+)([-+].*)?""".r

  def parse(s: String): Option[Version] = s match {
    case VersionPattern(major, minor, patch, null) => Some(ReleaseVersion(BigInt(major), BigInt(minor), BigInt(patch)))
    case VersionPattern(major, minor, patch, suffix) => Some(PreReleaseVersion(BigInt(major), BigInt(minor), BigInt(patch), suffix))
    case _ => None
  }

}

object ReleaseVersion {

  implicit object Ordering extends Ordering[ReleaseVersion] {
    override def compare(x: ReleaseVersion, y: ReleaseVersion): Int = {
      val cmp1 = x.major compare y.major
      if (cmp1 != 0) return cmp1
      val cmp2 = x.minor compare y.minor
      if (cmp2 != 0) return cmp2
      x.patch compare y.patch
    }
  }

}

case class ReleaseVersion(major: BigInt, minor: BigInt, patch: BigInt) extends Version {
  override def toString: String = s"$major.$minor.$patch"
}
case class PreReleaseVersion(major: BigInt, minor: BigInt, patch: BigInt, suffix: String) extends Version {
  override def toString: String = s"$major.$minor.$patch$suffix"
}
