package mill.api.daemon.internal

import java.nio.file.Path

private[mill] trait LauncherOutFiles extends AutoCloseable {
  def runId: String
  def consoleTail: Path
  def profile: Path = Path.of("out", "mill-profile.json")
  def chromeProfile: Path = Path.of("out", "mill-chrome-profile.json")
  def dependencyTree: Path = Path.of("out", "mill-dependency-tree.json")
  def invalidationTree: Path = Path.of("out", "mill-invalidation-tree.json")

  def publishLiveArtifacts(): Unit = ()

  def publishArtifacts(): Unit = ()
}

private[mill] object LauncherOutFiles {

  object Noop extends LauncherOutFiles {
    override def runId: String = "noop"
    override def consoleTail: Path = Path.of("out", "mill-console-tail")
    override def close(): Unit = ()
  }
}
