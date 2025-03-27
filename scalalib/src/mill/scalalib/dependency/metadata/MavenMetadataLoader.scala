package mill.scalalib.dependency.metadata

import coursier.maven.MavenRepository
import coursier.util.Task
import mill.scalalib.dependency.versions.Version

import java.time.Clock

private[dependency] final case class MavenMetadataLoader(mavenRepo: MavenRepository)
    extends MetadataLoader {

  private val cache = coursier.cache.FileCache[Task]()

  override def getVersions(module: coursier.Module, clock: Clock): List[Version] = {
    // TODO fallback to 'versionsFromListing' if 'versions' doesn't work? (needs to be made public in coursier first)
    val allVersions =
      mavenRepo.versions(module, cache.withClock(clock).fetch).run.unsafeRun()(cache.ec)
    allVersions
      .map(_._1.available.map(Version(_)))
      .getOrElse(List.empty)
  }
}
