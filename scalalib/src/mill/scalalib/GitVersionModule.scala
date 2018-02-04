package mill
package scalalib

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.scalalib.semver.{ReleaseVersion, Version}

import scala.util.matching.Regex

object GitVersionModule {

  val MinorMessage: Regex = """(?i)\[minor\].*""".r
  val MajorMessage: Regex = """(?i)\[major\].*""".r

  case class CommitInfo(hash: String, message: String, version: Option[Version])

  def tagVersions(tags: Seq[(String, String)]): Seq[(String, Version)] =
    tags.flatMap { case (commit, tag) =>
      Version.parse(tag).map(commit -> _)
    }

  def releaseVersions(versions: Seq[Version]): Seq[ReleaseVersion] =
    versions.collect { case r: ReleaseVersion => r }

  def latestReleaseVersion(versions: Seq[ReleaseVersion]): Option[ReleaseVersion] =
    if (versions.nonEmpty) Some(versions.max) else None

  def latestReleaseCommit(tagVersions: Seq[(String, Version)]): Option[(String, ReleaseVersion)] = {
    latestReleaseVersion(releaseVersions(tagVersions.map(_._2))).flatMap { latest =>
      tagVersions.find(_._2 == latest).map(_._1 -> latest)
    }
  }

  def bumpCommitVersion(lastRelease: ReleaseVersion,
                        commit: CommitInfo): ReleaseVersion = {
    commit.version match {
      case Some(cv) => cv.baseVersion
      case None => commit.message match {
        case MajorMessage() => lastRelease.copy(major = lastRelease.major + 1, 0, 0)
        case MinorMessage() => lastRelease.copy(minor = lastRelease.minor + 1, patch = 0)
        case _ => lastRelease.copy(patch = lastRelease.patch + 1)
      }
    }
  }

  def inferVersion(git: Git): String = {
    val tags = git.tags
    val versions = tagVersions(tags)
    val (since, releaseVersion) = GitVersionModule.latestReleaseCommit(versions) match {
      case Some((c, v)) => Some(c) -> v
      case None => None -> ReleaseVersion(0, 0, 0)
    }
    val commits = git.commitsBetween(since, None)
    if (commits.isEmpty) "0.0.0" else {
      val versionsMap = versions.toMap
      val (currentHash, _) = commits.last
      versionsMap.get(currentHash) match {
        case Some(tagged) => tagged.toString
        case None =>
          val bumpedBase = commits.map {
            case (commit, message) =>
              val commitVersion = versionsMap.get(commit)
              bumpCommitVersion(releaseVersion, CommitInfo(commit, message, commitVersion))
          }.max
          s"$bumpedBase-${commits.length - since.size}+${currentHash.take(8)}"
      }
    }
  }

}

trait GitVersionModule extends mill.Module {
  this: PublishModule =>

  lazy val git: Git = new GitCLI

  override def publishVersion: T[String] = T {
    GitVersionModule.inferVersion(git)
  }

}

trait Git {
  // Returns tuple of (commit hash, tag)
  def tags: Seq[(String, String)]
  // Returns tuple of (commit hash, message)
  def commitsBetween(refFrom: Option[String], refTo: Option[String]): Seq[(String, String)]
}

class GitCLI extends Git {

  private def split(s: String): Option[(String, String)] = s.split(" ", 2) match {
    case Array(a, b) => Some(a -> b)
    case _ => None
  }

  def tags: Seq[(String, String)] =
    %%("git", "tag", "--color=never", "--list", "--format=%(objectname) %(refname:strip=2)")
      .out.lines.flatMap(split)

  def commitsBetween(refFrom: Option[String], refTo: Option[String]): Seq[(String, String)] = {
    val cmd =
      Seq("git", "log", "--color=never", "--pretty=%H %s") ++
        refFrom.map("^" + _) :+
        refTo.getOrElse("HEAD")
    %%(cmd).out.lines.flatMap(split)
  }

}