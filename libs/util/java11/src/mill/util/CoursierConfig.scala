package mill.util

import com.lihaoyi.unroll
import coursier.CoursierEnv
import coursier.cache.{CacheEnv, CachePolicy}
import coursier.core.Repository
import coursier.credentials.Credentials
import coursier.params.Mirror

import scala.util.{Success, Try}
import scala.concurrent.duration.Duration

final case class CoursierConfig(
    repositories: Seq[Repository],
    confFiles: Seq[os.Path],
    mirrors: Seq[Mirror],
    cacheLocation: String,
    archiveCacheLocation: String,
    credentials: Seq[Credentials],
    ttl: Option[Duration],
    cachePolicies: Seq[CachePolicy],
    @unroll
    userAgent: Option[String] = None
)

object CoursierConfig {
  // as much as possible, prefer using CoursierConfigModule.coursierConfig(),
  // that reads up-to-date values from the environment and from Java properties
  private[mill] def default(): CoursierConfig = {
    val envRepos = CoursierEnv.defaultRepositories(
      CoursierEnv.repositories.read(),
      CoursierEnv.scalaCliConfig.read()
    )
    CoursierConfig(
      Jvm.reposFromStrings(mill.api.BuildCtx.millRepositories).get ++ envRepos,
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
      ),
      Some(defaultUserAgent())
    )
  }

  /** Java property that overrides the `User-Agent` Mill sends to repositories, as for coursier */
  private[mill] val userAgentProperty = "coursier.http.agent"

  /**
   * The `User-Agent` Mill sends to repositories when fetching metadata or artifacts:
   * Mill's own one (see [[userAgent]]), unless overridden with the `coursier.http.agent`
   * Java property, the way coursier lets its default agent be overridden.
   */
  private[mill] def defaultUserAgent(props: Map[String, String] = sys.props.toMap): String =
    props.getOrElse(
      userAgentProperty,
      userAgent(BuildInfo.millVersion, BuildInfo.coursierVersion)
    )

  /**
   * A Mill-specific `User-Agent`, as Maven Central asks tools to send, e.g.
   * `Mill/1.3 (https://github.com/com-lihaoyi/mill) Coursier/2.1`
   */
  private[mill] def userAgent(millVersion: String, coursierVersion: String): String = {
    def versionSuffix(version: String): String =
      Try(Version.parse(version)) match {
        case Success(v) => s"/${v.major}${v.minor.fold("")("." + _)}"
        case _ => ""
      }
    s"Mill${versionSuffix(millVersion)} (${BuildInfo.millProjectUrl}) " +
      s"Coursier${versionSuffix(coursierVersion)}"
  }
}
