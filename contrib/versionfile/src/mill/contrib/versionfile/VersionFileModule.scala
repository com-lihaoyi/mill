package mill.contrib.versionfile

import mill._

trait VersionFileModule extends Module {

  /** The file containing the current version. */
  def versionFile: T[PathRef] = Task.Source("version")

  /** The current version. */
  def currentVersion: T[Version] = Task { Version.of(os.read(versionFile().path).trim) }

  /** The release version. */
  def releaseVersion: T[Version] = Task { currentVersion().asRelease }

  /** The next snapshot version. */
  def nextVersion(bump: String): Task[Version] =
    Task.Anon { currentVersion().asSnapshot.bump(bump) }

  /** Writes the release version to file. */
  def setReleaseVersion(): Command[Unit] = Task.Command {
    setVersionTask(releaseVersion)()
  }

  /** Writes the next snapshot version to file. */
  def setNextVersion(bump: String): Command[Unit] = Task.Command {
    setVersionTask(nextVersion(bump))()
  }

  /** Writes the given version to file. */
  def setVersion(version: Task[Version]): Command[Unit] = Task.Command {
    setVersionTask(version)()
  }

  protected def setVersionTask(version: Task[Version]) = Task.Anon {
    Task.log.info(generateCommitMessage(version()))
    writeVersionToFile(versionFile(), version())
  }

  def writeVersionToFile(versionFile: mill.api.PathRef, version: Version): Unit =
    os.write.over(
      versionFile.path,
      version.toString
    )

  /** Procs for tagging current version and committing changes. */
  def tag = Task {
    Seq(
      os.proc("git", "commit", "-am", generateCommitMessage(currentVersion())),
      os.proc("git", "tag", currentVersion().toString)
    )
  }

  /** Procs for committing changes and pushing. */
  def push = Task {
    Seq(
      os.proc("git", "commit", "-am", generateCommitMessage(currentVersion())),
      os.proc("git", "push", "origin", "master", "--tags")
    )
  }

  def generateCommitMessage(version: Version): String =
    version match {
      case release: Version.Release => s"Setting release version to $version"
      case snapshot: Version.Snapshot => s"Setting next version to $version"
    }

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
  def exec(procs: mill.util.Tasks[Seq[os.proc]]) = Task.Command {
    for {
      procs <- Task.sequence(procs.value)()
      proc <- procs
    } yield proc.call()
  }

  lazy val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
}
