package mill.javalib.dependency.metadata

import java.time.Clock

import coursier.Repository
import coursier.maven.MavenRepository

private[dependency] object MetadataLoaderFactory {
  def apply(
      repo: Repository,
      offline: Boolean = false,
      clock: Clock = Clock.systemDefaultZone()
  ): Option[MetadataLoader] = repo match {
    case mavenRepo: MavenRepository => Some(MavenMetadataLoader(mavenRepo, offline, clock))
    case _ => None
  }
}
