package mill.contrib.versionfile

import mill._, scalalib._

trait VersionFileModule extends Module {

  /** The file containing the current version. */
  def versionFile: Source = T.source(millSourcePath / "version")

  /** The current version. */
  def currentVersion: T[Version] = T { Version.of(os.read(versionFile().path).trim) }

  /** The release version. */
  def releaseVersion = T { currentVersion().asRelease }

  /** The next snapshot version. */
  def nextVersion(bump: String) = T.command { currentVersion().asSnapshot.bump(bump) }

  /** Writes the release version to file. */
  def setReleaseVersion = T {
    setVersionTask(releaseVersion)()
  }

  /** Writes the next snapshot version to file. */
  def setNextVersion(bump: String) = T.command {
    setVersionTask(nextVersion(bump))()
  }

  /** Writes the given version to file. */
  def setVersion(version: Version) = T.command {
    setVersionTask(T.task { version })()
  }

  protected def setVersionTask(version: T[Version]) = T.task {
    T.log.info(generateCommitMessage(version()))
    writeVersionToFile(versionFile(), version())
  }

  def writeVersionToFile(versionFile: mill.api.PathRef, version: Version) =
    os.write.over(
      versionFile.path,
      version.toString
    )

  /** Procs for tagging current version and committing changes. */
  def tag = T {
    Seq(
      os.proc("git", "commit", "-am", generateCommitMessage(currentVersion())),
      os.proc("git", "tag", currentVersion().toString)
    )
  }

  /** Procs for committing changes and pushing. */
  def push = T {
    Seq(
      os.proc("git", "commit", "-am", generateCommitMessage(currentVersion())),
      os.proc("git", "push", "origin", "master", "--tags")
    )
  }

  def generateCommitMessage(version: Version) =
    version match {
      case release: Version.Release => s"Setting release version to $version"
      case snapshot: Version.Snapshot => s"Setting next version to $version"
    }

  import upickle.core._
  import upickle.default._

  implicit val shellableReadWriter: ReadWriter[os.Shellable] =
    readwriter[Seq[String]].bimap(
      _.value,
      os.Shellable(_)
    )

  implicit val procReadWriter: ReadWriter[os.proc] =
    readwriter[Seq[os.Shellable]].bimap(
      _.command,
      os.proc(_)
    )
}

object VersionFileModule extends define.ExternalModule {

  /** Executes the given processes. */
  def exec(procs: mill.main.Tasks[Seq[os.proc]]) = T.command {
    for {
      procs <- T.sequence(procs.value)()
      proc <- procs
    } yield proc.call()
  }

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
