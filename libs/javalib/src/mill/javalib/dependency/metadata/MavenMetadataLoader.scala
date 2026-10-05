package mill.javalib.dependency.metadata

import java.time.Clock

import scala.util.chaining.given

import coursier.cache.CachePolicy.LocalOnly
import coursier.cache.{Cache, FileCache}
import coursier.maven.MavenRepository
import coursier.util.Task
import mill.api.Logger
import mill.javalib.dependency.versions.Version

private[dependency] final case class MavenMetadataLoader(
    mavenRepo: MavenRepository,
    offline: Boolean,
    clock: Clock,
    log: Logger
) extends MetadataLoader {

  private val cache = Cache.default match {
    case cache: FileCache[Task] =>
      cache
        .withClock(clock)
        .pipe { cache =>
          if (offline) cache.withCachePolicies(Seq(LocalOnly))
          else cache
        }
    case cache =>
      mill.util.CoursierCacheSupport.warnNotFileCache(
        cache,
        Seq("the fixed clock used for TTL checks", "offline mode"),
        log.warn(_)
      )
      cache
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
