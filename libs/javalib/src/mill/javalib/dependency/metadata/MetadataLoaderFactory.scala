package mill.javalib.dependency.metadata

import java.time.Clock

import coursier.Repository
import coursier.maven.MavenRepository
import mill.api.Logger

private[dependency] object MetadataLoaderFactory {
  def apply(
      repo: Repository,
      log: Logger,
      offline: Boolean = false,
      clock: Clock = Clock.systemDefaultZone()
  ): Option[MetadataLoader] = repo match {
    case mavenRepo: MavenRepository => Some(MavenMetadataLoader(mavenRepo, offline, clock, log))
    case _ => None
  }
}
