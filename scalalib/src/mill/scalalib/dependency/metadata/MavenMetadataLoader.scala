package mill.scalalib.dependency.metadata

import coursier.Cache
import coursier.maven.MavenRepository
import mill.scalalib.dependency.versions.Version

private[dependency] case class MavenMetadataLoader(mavenRepo: MavenRepository)
    extends MetadataLoader {

  private val fetch = Cache.fetch()

  override def getVersions(module: coursier.Module): List[Version] = {
    // TODO fallback to versionsFromListing if versions doesn't work? (needs to be made public in coursier first)
    val allVersions = mavenRepo.versions(module, fetch).run.unsafePerformSync
    allVersions
      .map(_.available.map(Version(_)))
      .getOrElse(List.empty)
  }
}
