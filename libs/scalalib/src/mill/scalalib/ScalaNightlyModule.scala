package mill.scalalib

import mill.*
import mill.api.Result
import mill.util.{Jvm, Version}

/**
 * Mix this trait into a [[ScalaModule]] to build it with a Scala 3 nightly version.
 *
 * It adds the [[CoursierModule.KnownRepositories.ScalaLangNightlies]] repository
 * to [[repositories]], and sets [[scalaVersion]] to the newest nightly version
 * found there, optionally restricted to the versions matching
 * [[scalaNightlyVersionPrefix]]. You can also override [[scalaVersion]] to pin
 * a specific nightly version, while still getting the nightly repository.
 *
 * Nightly versions are meant for testing upcoming compiler changes in your
 * project, not for building artifacts you publish or deploy.
 */
trait ScalaNightlyModule extends ScalaModule {

  /**
   * Restricts the nightly versions [[scalaNightlyVersion]] picks from to the ones
   * starting with this prefix, e.g. `"3.8"` or `"3.8.3"`. The prefix is matched on
   * whole version segments, so `"3.1"` doesn't match `3.10.x` nightlies.
   * Empty by default, meaning the newest nightly overall is picked.
   */
  def scalaNightlyVersionPrefix: T[String] = Task { "" }

  override def repositories: T[Seq[String]] = Task {
    super.repositories() ++ Seq(CoursierModule.KnownRepositories.ScalaLangNightlies)
  }

  /**
   * All the Scala 3 nightly versions available in [[repositories]].
   *
   * This is re-checked on every run, but the repository metadata listing the
   * versions is cached by coursier (see `COURSIER_TTL`, 24 hours by default),
   * and only the cached metadata is used when running with `--offline`.
   */
  def scalaNightlyVersions: T[Seq[String]] = Task.Uncached {
    val result = Jvm.versions(
      repositories = repositoriesTask(),
      module = coursier.Module(
        coursier.Organization("org.scala-lang"),
        coursier.ModuleName("scala3-compiler_3")
      ),
      ctx = Some(Task.ctx()),
      coursierCacheCustomizer = coursierCacheCustomizer(),
      config = coursierConfigModule().coursierConfig()
    )
    val nightlies = result.versions.available.filter(ScalaNightlyModule.NightlyVersion.matches)
    if (nightlies.nonEmpty) Result.Success(nightlies)
    else {
      val errors = result.results.collect { case (repo, Left(err)) => s"  ${repo.repr}: $err" }
      val errorsMsg =
        if (errors.isEmpty) "" else errors.mkString("\nRepository errors:\n", "\n", "")
      Result.Failure(s"No Scala nightly version found$errorsMsg")
    }
  }

  /**
   * The newest version from [[scalaNightlyVersions]] matching [[scalaNightlyVersionPrefix]].
   *
   * This is only re-computed when [[scalaNightlyVersions]] or
   * [[scalaNightlyVersionPrefix]] change, and warns when the picked version
   * differs from the one picked previously.
   */
  def scalaNightlyVersion: T[String] = Task(persistent = true) {
    val prefix = scalaNightlyVersionPrefix()
    ScalaNightlyModule.latestNightlyVersion(scalaNightlyVersions(), prefix) match {
      case Some(version) =>
        val previousFile = Task.dest / "previous-version.txt"
        val previous = Option.when(os.isFile(previousFile))(os.read(previousFile))
        ScalaNightlyModule.versionChangeMessage(previous, version).foreach(Task.log.warn(_))
        os.write.over(previousFile, version)
        Result.Success(version)
      case None =>
        Result.Failure(s"No Scala nightly version with prefix '$prefix' found")
    }
  }

  override def scalaVersion: T[String] = Task { scalaNightlyVersion() }
}

object ScalaNightlyModule {

  /**
   * Matches Scala 3 nightly versions, e.g. `3.8.3-RC1-bin-20260128-3882481-NIGHTLY`
   */
  val NightlyVersion: scala.util.matching.Regex =
    raw"""3\.\d+\.\d+(-RC\d+)?-bin-\d+-\w+-NIGHTLY""".r

  private[scalalib] def versionChangeMessage(
      previous: Option[String],
      current: String
  ): Option[String] = {
    val disclaimer = "Scala nightly versions are meant for testing upcoming compiler " +
      "changes, not for building published or deployed artifacts"
    previous match {
      case Some(`current`) => None
      case Some(prev) => Some(s"Scala nightly version changed from $prev to $current. $disclaimer")
      case None => Some(s"Using Scala nightly version $current. $disclaimer")
    }
  }

  /**
   * Picks the newest Scala 3 nightly version from `versions`, among the ones
   * matching `prefix` on whole version segments (an empty prefix matches all).
   */
  def latestNightlyVersion(versions: Seq[String], prefix: String): Option[String] = {
    def matchesPrefix(version: String) =
      prefix.isEmpty || version == prefix || version.startsWith(prefix) && {
        val next = version.charAt(prefix.length)
        next == '.' || next == '-'
      }

    versions
      .filter(v => NightlyVersion.matches(v) && matchesPrefix(v))
      .maxByOption(Version.parse)(using Version.MavenOrdering)
  }
}
