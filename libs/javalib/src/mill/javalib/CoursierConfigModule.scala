package mill.javalib

import coursier.CoursierEnv
import coursier.cache.{CacheEnv, CachePolicy}
import coursier.core.Repository
import coursier.credentials.Credentials
import coursier.params.Mirror
import coursier.util.{EnvEntry, EnvValues}
import mill.api.*
import mill.{T, Task}

import scala.concurrent.duration.Duration

/**
 * Reads coursier environment variables and Java properties
 *
 * This module reads environment variables and Java properties, and uses
 * them to get default values for a number of coursier parameters, such as:
 *
 * * the coursier cache location, read from `COURSIER_CACHE` in the environment
 *   and `coursier.cache` in Java properties. It is recommended to use an
 *   absolute path rather than a relative one.
 *
 * * the coursier archive cache location, read from `COURSIER_ARCHIVE_CACHE` in
 *   the environment and in `coursier.archive.cache` in Java properties.
 *
 * * credentials, read from `COURSIER_CREDENTIALS` in the environment, and
 *   `coursier.credentials` in Java properties. Its format is documented
 *   here: https://github.com/coursier/coursier/blob/701e93664855709db38e443cb7a19e36ddc49b78/doc/docs/other-credentials.md
 *
 * * TTL for snapshot artifacts / version listings / etc., read from
 *   `COURSIER_TTL` in the environment and `coursier.ttl` in Java properties.
 *   This value is parsed with `scala.concurrent.duration.Duration`, and accepts
 *   formats like "5 hours", "1 day", etc. or just "0".
 *
 * * default repositories, read from `COURSIER_REPOSITORIES` in the environment
 *   and `coursier.repositories` in Java properties. It can be set to values like
 *   "ivy2Local|central|https://maven.google.com". Its format is documented here:
 *   https://github.com/coursier/coursier/blob/701e93664855709db38e443cb7a19e36ddc49b78/doc/docs/other-repositories.md
 *
 * * mirror repository files, read from `COURSIER_MIRRORS` and `coursier.mirrors`,
 *   whose file they point to should contains things like
 *
 *       google.from=https://repo1.maven.org/maven2
 *       google.to=https://maven.google.com
 *
 * Environment variables always have precedence over Java properties.
 */
object CoursierConfigModule extends ExternalModule with CoursierConfigModule {
  lazy val millDiscover = Discover[this.type]
}

trait CoursierConfigModule extends Module {

  // All environment / Java properties "entries" that we're going to read.
  // These are read all at once via coursierEnv.
  private val entries = Seq(
    CacheEnv.cache,
    CacheEnv.credentials,
    CacheEnv.ttl,
    CacheEnv.cachePolicy,
    CacheEnv.archiveCache,
    CoursierEnv.mirrors,
    CoursierEnv.mirrorsExtra,
    CoursierEnv.repositories,
    CoursierEnv.scalaCliConfig,
    CoursierEnv.configDir
  )

  private val envVars = entries.map(_.envName).toSet
  private val propNames = entries.map(_.propName).toSet

  extension (entry: EnvEntry)
    private def readFrom(env: Map[String, String], props: Map[String, String]): EnvValues =
      EnvValues(env.get(entry.envName), props.get(entry.propName))

  /**
   * Environment variables and Java properties, and their values, used by coursier
   */
  def coursierEnv: T[(env: Map[String, String], props: Map[String, String])] = Task.Input {
    val env = Task.env.filterKeys(envVars).toMap
    val props =
      propNames.iterator.flatMap(name => sys.props.get(name).iterator.map(name -> _)).toMap
    (env, props)
  }

  /** Default repositories for dependency resolution */
  def defaultRepositories: Task[Seq[Repository]] = Task.Anon {
    val (env, props) = coursierEnv()
    CoursierEnv.defaultRepositories(
      CoursierEnv.repositories.readFrom(env, props),
      CoursierEnv.scalaCliConfig.readFrom(env, props)
    )
  }

  /** Default JSON configuration files to be used by coursier */
  def defaultConfFiles: Task[Seq[PathRef]] = Task.Anon {
    val (env, props) = coursierEnv()
    CacheEnv.defaultConfFiles(CacheEnv.scalaCliConfig.readFrom(env, props))
      .map(os.Path(_, BuildCtx.workspaceRoot))
      .map(PathRef(_))
  }

  /** Default mirrors to be used by coursier */
  def defaultMirrors: Task[Seq[Mirror]] = Task.Anon {
    val (env, props) = coursierEnv()
    CoursierEnv.defaultMirrors(
      CoursierEnv.mirrors.readFrom(env, props),
      CoursierEnv.mirrorsExtra.readFrom(env, props),
      CoursierEnv.scalaCliConfig.readFrom(env, props),
      CoursierEnv.configDir.readFrom(env, props)
    )
  }

  /** Default cache location to be used by coursier */
  def defaultCacheLocation: Task[String] = Task.Anon {
    val (env, props) = coursierEnv()
    val path: java.nio.file.Path = CacheEnv.defaultCacheLocation(
      CacheEnv.cache.readFrom(env, props)
    )
    path.toString
  }

  /**
   * Default archive cache location to be used by coursier
   *
   * This is the directory under which archives are unpacked
   * by `coursier.cache.ArchiveCache`. This includes JVMs
   * managed by coursier, which Mill relies on by default.
   */
  def defaultArchiveCacheLocation: Task[String] = Task.Anon {
    val (env, props) = coursierEnv()
    val path: java.nio.file.Path = CacheEnv.defaultArchiveCacheLocation(
      CacheEnv.archiveCache.readFrom(env, props)
    )
    path.toString
  }

  /** Default repository credentials to be used by coursier */
  def defaultCredentials: Task[Seq[Credentials]] = Task.Anon {
    val (env, props) = coursierEnv()
    CacheEnv.defaultCredentials(
      CacheEnv.credentials.readFrom(env, props),
      CacheEnv.scalaCliConfig.readFrom(env, props),
      CacheEnv.configDir.readFrom(env, props)
    )
  }

  /** Default TTL used by coursier when downloading changing artifacts such as snapshots */
  def defaultTtl: Task[Option[Duration]] = Task.Anon {
    val (env, props) = coursierEnv()
    CacheEnv.defaultTtl(
      CacheEnv.ttl.readFrom(env, props)
    )
  }

  /** Default cache policies used by coursier when downloading artifacts */
  def defaultCachePolicies: Task[Seq[CachePolicy]] = Task.Anon {
    val (env, props) = coursierEnv()
    CacheEnv.defaultCachePolicies(
      CacheEnv.cachePolicy.readFrom(env, props)
    )
  }

  /**
   * Aggregates coursier parameters read from the environment
   */
  def coursierConfig: Task[mill.util.CoursierConfig] = Task.Anon {
    mill.util.CoursierConfig(
      defaultRepositories(),
      defaultConfFiles().map(_.path),
      defaultMirrors(),
      defaultCacheLocation(),
      defaultArchiveCacheLocation(),
      defaultCredentials(),
      defaultTtl(),
      defaultCachePolicies()
    )
  }
}
