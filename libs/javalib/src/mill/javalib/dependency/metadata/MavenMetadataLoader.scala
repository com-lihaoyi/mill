package mill.javalib.dependency.metadata

import java.time.Clock

import scala.util.chaining.given

import coursier.cache.CachePolicy.LocalOnly
import coursier.maven.MavenRepository
import coursier.util.Task
import mill.javalib.dependency.versions.Version

private[dependency] final case class MavenMetadataLoader(
    mavenRepo: MavenRepository,
    offline: Boolean,
    clock: Clock
) extends MetadataLoader {

  private val cache = coursier.cache.FileCache[Task]()
    .withClock(clock)
    .pipe { cache =>
      if (offline) cache.withCachePolicies(Seq(LocalOnly))
      else cache
    }

  override def getVersions(module: coursier.Module): List[Version] = {
    // TODO fallback to 'versionsFromListing' if 'versions' doesn't work? (needs to be made public in coursier first)
    val allVersions = cache.logger.use(mavenRepo.versions(module, cache.fetch).run)
      .unsafeRun()(using cache.ec)
    allVersions
      .map(_._1.available.map(Version(_)))
      .getOrElse(List.empty)
  }
}
