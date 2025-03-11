package mill.scalalib.dependency.metadata

import mill.scalalib.dependency.versions.Version

import java.time.Clock

private[dependency] trait MetadataLoader {
  def getVersions(module: coursier.Module, clock: Clock): Seq[Version]
}
