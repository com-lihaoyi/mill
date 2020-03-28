package mill.contrib.versionfile

import mill._, scalalib._

trait VersionFileModule extends Module {

  implicit val wd = os.pwd

  /** The file containing the current version. */
  def versionFile: define.Source = T.source(millSourcePath / "version")
  /** The current version. */
  def currentVersion: T[Version] = T { Version.of(os.read(versionFile().path)) }
  /** The release version. */
  def releaseVersion = T { currentVersion().asRelease }
  /** The next snapshot version. */
  def nextVersion(bump: String) = T.command { currentVersion().asSnapshot.bump(bump) }

  def setReleaseVersion = T {
    val commitMessage = s"Setting release version to ${releaseVersion()}"
    
    T.ctx.log.info(commitMessage)

    os.write.over(
      versionFile().path,
      releaseVersion().toString
    )

    os.proc("git", "commit", "-am", commitMessage).call()
    os.proc("git", "tag", releaseVersion().toString).call()
  }

  def setNextVersion(bump: String) = T.command {
    val commitMessage = s"Setting next version to ${nextVersion(bump)()}"

    T.ctx.log.info(commitMessage)

    os.write.over(
      versionFile().path,
      nextVersion(bump)().toString
    )

    os.proc("git", "commit", "-am", commitMessage).call()
    os.proc("git", "push", "origin", "master", "--tags").call()
  }
}
