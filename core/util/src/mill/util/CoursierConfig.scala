package mill.util

import coursier.CoursierEnv
import coursier.cache.{CacheEnv, CachePolicy}
import coursier.core.Repository
import coursier.credentials.Credentials
import coursier.params.Mirror

import scala.concurrent.duration.Duration

final case class CoursierConfig(
    repositories: Seq[Repository],
    confFiles: Seq[os.Path],
    mirrors: Seq[Mirror],
    cacheLocation: String,
    archiveCacheLocation: String,
    credentials: Seq[Credentials],
    ttl: Option[Duration],
    cachePolicies: Seq[CachePolicy]
)

object CoursierConfig {
  // as much as possible, prefer using CoursierConfigModule.coursierConfig(),
  // that reads up-to-date values from the environment and from Java properties
  private[mill] def default(): CoursierConfig =
    CoursierConfig(
      CoursierEnv.defaultRepositories(
        CoursierEnv.repositories.read(),
        CoursierEnv.scalaCliConfig.read()
      ),
      CacheEnv.defaultConfFiles(CacheEnv.scalaCliConfig.read())
        .map(os.Path(_)),
      CoursierEnv.defaultMirrors(
        CoursierEnv.mirrors.read(),
        CoursierEnv.mirrorsExtra.read(),
        CoursierEnv.scalaCliConfig.read(),
        CoursierEnv.configDir.read()
      ),
      CacheEnv.defaultCacheLocation(CacheEnv.cache.read()).toString,
      CacheEnv.defaultArchiveCacheLocation(CacheEnv.archiveCache.read()).toString,
      CacheEnv.defaultCredentials(
        CacheEnv.credentials.read(),
        CacheEnv.scalaCliConfig.read(),
        CacheEnv.configDir.read()
      ),
      CacheEnv.defaultTtl(
        CacheEnv.ttl.read()
      ),
      CacheEnv.defaultCachePolicies(
        CacheEnv.cachePolicy.read()
      )
    )
}
