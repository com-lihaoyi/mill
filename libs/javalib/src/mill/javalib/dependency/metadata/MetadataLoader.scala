package mill.javalib.dependency.metadata

import mill.javalib.dependency.versions.Version

private[dependency] trait MetadataLoader {
  def getVersions(module: coursier.Module): Seq[Version]
}
