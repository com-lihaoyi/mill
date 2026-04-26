package mill.api.daemon.internal

import mill.constants.DaemonFiles
import mill.constants.OutFiles

import java.nio.file.Path

private[mill] trait LauncherOutFiles extends AutoCloseable {
  def runId: String
  def consoleTail: Path
  def profile: Path
  def chromeProfile: Path
  def dependencyTree: Path
  def invalidationTree: Path

  def publishLiveArtifacts(): Unit = ()

  def publishArtifacts(): Unit = ()
}

private[mill] object LauncherOutFiles {
  def noop(out: Path): LauncherOutFiles = new LauncherOutFiles {
    override def runId: String = "noop"
    override val consoleTail: Path = out.resolve(DaemonFiles.millConsoleTail)
    override val profile: Path = out.resolve(OutFiles.millProfile)
    override val chromeProfile: Path = out.resolve(OutFiles.millChromeProfile)
    override val dependencyTree: Path = out.resolve(OutFiles.millDependencyTree)
    override val invalidationTree: Path = out.resolve(OutFiles.millInvalidationTree)
    override def close(): Unit = ()
  }
}
