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

  /** Returns a per-run path for a well-known `out/` artifact. */
  def artifactPath(default: Path): Path = default

  /** Publishes this run's well-known artifacts as the latest visible ones under `out/`. */
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
