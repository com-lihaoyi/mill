package mill.api.daemon.internal

import java.nio.file.Path

/**
 * A per-run handle for routing well-known `out/` artifact files (like
 * `mill-chrome-profile.json`, `mill-console-tail`, etc.) into a per-run
 * subdirectory, and for publishing those files as the "latest" version
 * visible at the top-level `out/` path via symlinks.
 *
 * Separate from [[LauncherLocking]] — callers that need to serialize access
 * to artifacts acquire locks at their own callsites rather than having
 * artifact routing entangled with locking.
 */
private[mill] trait LauncherOutFiles extends AutoCloseable {
  def runId: String
  def consoleTail: Path
  def profile: Path = Path.of("out", "mill-profile.json")
  def chromeProfile: Path = Path.of("out", "mill-chrome-profile.json")
  def dependencyTree: Path = Path.of("out", "mill-dependency-tree.json")
  def invalidationTree: Path = Path.of("out", "mill-invalidation-tree.json")

  /**
   * Publishes only the live console-tail symlink so concurrent observers can
   * tail this run's log while it's still running. Other artifact symlinks are
   * intentionally NOT yet updated: they would point at not-yet-written files
   * and appear broken to observers reading mid-run.
   */
  def publishLiveArtifacts(): Unit = ()

  /**
   * Publishes this run's completed artifacts (profile / dependency tree / etc.)
   * as the latest visible ones under `out/`. Call after evaluation finishes.
   */
  def publishArtifacts(): Unit = ()
}

private[mill] object LauncherOutFiles {

  /**
   * Non-routing implementation used in non-daemon mode where per-run
   * subdirectories are not produced.
   */
  object Noop extends LauncherOutFiles {
    override def runId: String = "noop"
    override def consoleTail: Path = Path.of("out", "mill-console-tail")
    override def close(): Unit = ()
  }
}
