package mill.contrib.versionfile

import scala.util.matching.Regex
sealed trait Version {

  import Version._

  val major: Int
  val minor: Int
  val patch: Int

  override def toString: String = this match {
    case Release(major, minor, patch) => s"$major.$minor.$patch"
    case Snapshot(major, minor, patch) => s"$major.$minor.$patch-SNAPSHOT"
  }

  def asRelease: Version = this match {
    case release @ Release(_, _, _) => release
    case Snapshot(major, minor, patch) => Release(major, minor, patch)
  }

  def asSnapshot: Version = this match {
    case Release(major, minor, patch) => Snapshot(major, minor, patch)
    case snapshot @ Snapshot(_, _, _) => snapshot
  }

  def bump(segment: String): Version = {
    val segments = segment match {
      case Bump.major => (major + 1, 0, 0)
      case Bump.minor => (major, minor + 1, 0)
      case Bump.patch => (major, minor, patch + 1)
      case _ =>
        throw new RuntimeException(s"Valid arguments for bump are: ${Bump.values.mkString(", ")}")
    }

    this match {
      case _: Release => Release.apply.tupled(segments)
      case _: Snapshot => Snapshot.apply.tupled(segments)
    }
  }
}

object Bump {
  val major = "major"
  val minor = "minor"
  val patch = "patch"

  val values: Seq[String] = Seq(major, minor, patch)
}

object Version {
  def of(version: String): Version =
    version match {
      case ReleaseVersion(major, minor, patch) =>
        Release(major.toInt, minor.toInt, patch.toInt)

      case MinorSnapshotVersion(major, minor, patch) =>
        Snapshot(major.toInt, minor.toInt, patch.toInt)
    }

  case class Release(major: Int, minor: Int, patch: Int) extends Version
  case class Snapshot(major: Int, minor: Int, patch: Int) extends Version

  val ReleaseVersion: Regex = raw"""(\d+)\.(\d+)\.(\d+)""".r
  val MinorSnapshotVersion: Regex = raw"""(\d+)\.(\d+)\.(\d+)-SNAPSHOT""".r

  import upickle.default._

  implicit val readWriter: ReadWriter[Version] =
    readwriter[String].bimap(_.toString, Version.of)

  implicit val read: mainargs.TokensReader.Simple[Version] =
    new mainargs.TokensReader.Simple[Version] {
      def shortName = "<version>"
      def read(s: Seq[String]) = Right(Version.of(s.last))
    }
}
