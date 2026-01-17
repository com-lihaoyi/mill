package mill.launcher

import coursier.{Artifacts, Dependency, ModuleName, Organization, Resolve, VersionConstraint}
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.maven.MavenRepository
import coursier.util.Task
import coursier.core.Module
import mill.constants.{BuildInfo, OutFiles, OutFolderMode}
import upickle.default.*

import java.io.File

import scala.concurrent.ExecutionContext.Implicits.global
import mill.api.JsonFormatters.*
object CoursierClient {

  // Use the proper output directory that respects MILL_OUTPUT_DIR
  private val cacheDir =
    os.Path(OutFiles.OutFiles.outFor(OutFolderMode.REGULAR), os.pwd) / "mill-daemon" / "cache"

  /**
   * Single-entry disk cache for expensive Coursier resolutions, as even when everything
   * is already downloaded Coursier's cache checking can take >100ms
   *
   * Stores a JSON tuple of (cacheKey, data) in the cache file.
   */
  private def cached[T: ReadWriter](
      cacheFile: os.Path,
      cacheKey: String,
      // Make sure we validate the correctness of cache entries because they may have
      // been deleted or moved in between the cache being populated and us using it
      validate: T => Boolean
  )(compute: => T): T = {
    // Try to read from cache
    val cachedValue: Option[T] =
      if (os.exists(cacheFile)) {
        try {
          val content = os.read(cacheFile)
          val (storedKey, value) = read[(String, T)](content)
          if (storedKey == cacheKey && validate(value)) Some(value) else None
        } catch {
          case _: Exception => None
        }
      } else None

    cachedValue match {
      case Some(value) => value
      case None =>
        val value = compute
        os.write.over(cacheFile, write((cacheKey, value)), createFolders = true)
        value
    }
  }

  def resolveMillDaemon(): Seq[os.Path] = {
    val testOverridesRepos = Option(System.getenv("MILL_LOCAL_TEST_REPO"))
      .toSeq
      .flatMap(_.split(File.pathSeparator).toSeq)

    // Cache key includes mill version and any test override repos
    val cacheKey = s"${BuildInfo.millVersion}:${testOverridesRepos.sorted.mkString(":")}"

    cached[Seq[os.Path]](
      cacheFile = cacheDir / "mill-daemon-classpath",
      cacheKey = cacheKey,
      validate = paths => paths.forall(os.exists(_))
    ) {
      val coursierCache0 = FileCache[Task]()
        .withLogger(coursier.cache.loggers.RefreshLogger.create())

      val testOverridesMavenRepos = testOverridesRepos.map { path =>
        MavenRepository(os.Path(path).toURI.toASCIIString)
      }

      val artifactsResultOrError = {
        val resolve = Resolve()
          .withCache(coursierCache0)
          .withDependencies(Seq(Dependency(
            Module(Organization("com.lihaoyi"), ModuleName("mill-runner-daemon_3"), Map()),
            VersionConstraint(BuildInfo.millVersion)
          )))
          .withRepositories(testOverridesMavenRepos ++ Resolve.defaultRepositories)

        val result = resolve.either().flatMap { v =>
          Artifacts(coursierCache0)
            .withResolution(v)
            .eitherResult()
        }
        result match {
          case Left(e) => sys.error(e.toString())
          case Right(v) => v
        }
      }

      artifactsResultOrError.artifacts.map(_._2.toString).toSeq.map(os.Path(_))
    }
  }

  def resolveJavaHome(id: String, jvmIndexVersionOpt: Option[String]): os.Path = {
    val indexVersion = jvmIndexVersionOpt.getOrElse(mill.client.Versions.coursierJvmIndexVersion)

    val cacheKey = s"$id:$indexVersion" // Cache key includes jvm id and index version

    cached[os.Path](
      cacheFile = cacheDir / "java-home",
      cacheKey = cacheKey,
      validate = os.isDir(_)
    ) {
      val coursierCache0 = FileCache[Task]()
        .withLogger(coursier.cache.loggers.RefreshLogger.create())

      val jvmCache = JvmCache()
        .withArchiveCache(ArchiveCache().withCache(coursierCache0))
        .withIndex(
          JvmIndex.load(
            cache = coursierCache0,
            repositories = Resolve.defaultRepositories,
            indexChannel = JvmChannel.module(JvmChannel.centralModule(), version = indexVersion)
          )
        )

      val javaHome = JavaHome().withCache(jvmCache)
        // when given a version like "17", always pick highest version in the index
        // rather than the highest already on disk
        .withUpdate(true)

      os.Path(coursierCache0.logger.using(javaHome.get(id)).unsafeRun()(using coursierCache0.ec))
    }

  }
}
