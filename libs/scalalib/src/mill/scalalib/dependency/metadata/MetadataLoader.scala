package mill.scalalib.dependency.metadata

import mill.scalalib.dependency.versions.Version

private[dependency] trait MetadataLoader {
  def getVersions(module: coursier.Module): Seq[Version]
}
