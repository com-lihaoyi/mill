package mill.scalalib

import coursier.core.Version
import mill.*
import mill.api.Result
import mill.util.Jvm

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
   * The newest Scala 3 nightly version available in [[repositories]] and matching
   * [[scalaNightlyVersionPrefix]].
   *
   * This is re-checked on every run, but the repository metadata listing the
   * versions is cached by coursier (see `COURSIER_TTL`, 24 hours by default),
   * and only the cached metadata is used when running with `--offline`.
   */
  def scalaNightlyVersion: T[String] = Task.Uncached {
    val prefix = scalaNightlyVersionPrefix()
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
    ScalaNightlyModule.latestNightlyVersion(result.versions.available, prefix) match {
      case Some(version) =>
        Task.log.warn(
          s"Using Scala nightly version $version. Scala nightly versions are meant for " +
            "testing upcoming compiler changes, not for building published or deployed artifacts"
        )
        Result.Success(version)
      case None =>
        val prefixMsg = if (prefix.isEmpty) "" else s" with prefix '$prefix'"
        val errors = result.results.collect { case (repo, Left(err)) => s"  ${repo.repr}: $err" }
        val errorsMsg =
          if (errors.isEmpty) "" else errors.mkString("\nRepository errors:\n", "\n", "")
        Result.Failure(s"No Scala nightly version$prefixMsg found$errorsMsg")
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
      .maxByOption(Version(_))
  }
}
