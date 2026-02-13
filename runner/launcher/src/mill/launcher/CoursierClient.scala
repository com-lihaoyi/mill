package mill.launcher

import coursier.{Artifacts, Dependency, ModuleName, Organization, Resolve, VersionConstraint}
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.util.Task
import coursier.core.Module
import mill.constants.{BuildInfo, OutFiles, OutFolderMode}
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global
import mill.api.JsonFormatters.*
object CoursierClient {

  // Compute the cache directory based on outMode, respecting MILL_OUTPUT_DIR
  private def cacheDir(outMode: OutFolderMode): os.Path =
    os.Path(OutFiles.OutFiles.outFor(outMode), os.pwd) / "mill-daemon" / "cache"

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

  def resolveMillDaemon(outMode: OutFolderMode, millRepositories: Seq[String]): Seq[os.Path] = {
    // FIXME All the messing with COURSIER_REPOSITORIES assumes user override repos only via this env var,
    // rather than via Java properties or config files, whose use is less likely. Things might go wrong
    // if ever users rely on those.
    val overridesRepos = Option(System.getenv("COURSIER_REPOSITORIES"))
      .toSeq
      .flatMap(_.split('|').toSeq)

    val millRepositories0 = millRepositories.sorted

    val cacheKey =
      s"${BuildInfo.millVersion} ${overridesRepos.reverse.mkString("|")}|${millRepositories0.mkString("|")}"

    cached[Seq[os.Path]](
      cacheFile = cacheDir(outMode) / "mill-daemon-classpath",
      cacheKey = cacheKey,
      validate = paths => paths.forall(os.exists(_))
    ) {
      val coursierCache0 = FileCache[Task]()
        .withLogger(coursier.cache.loggers.RefreshLogger.create())

      val configuredRepos = mill.util.Jvm.reposFromStrings(millRepositories0).get

      val artifactsResultOrError = {
        // configuredRepos (from mill-repositories) comes first so user config takes precedence
        val allRepos = configuredRepos ++ Resolve.defaultRepositories
        val resolve = Resolve()
          .withCache(coursierCache0)
          .withDependencies(Seq(Dependency(
            Module(Organization("com.lihaoyi"), ModuleName("mill-runner-daemon_3"), Map()),
            VersionConstraint(BuildInfo.millVersion)
          )))
          .withRepositories(allRepos)

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

  def resolveJavaHome(
      id: String,
      jvmIndexVersionOpt: Option[String],
      outMode: OutFolderMode,
      millRepositories: Seq[String]
  ): os.Path = {
    val indexVersion = jvmIndexVersionOpt.getOrElse(mill.client.Versions.coursierJvmIndexVersion)
    val cacheKey = s"$id:$indexVersion:${millRepositories.sorted.mkString(":")}"

    cached[os.Path](
      cacheFile = cacheDir(outMode) / "java-home",
      cacheKey = cacheKey,
      validate = os.isDir(_)
    ) {
      val coursierCache0 = FileCache[Task]()
        .withLogger(coursier.cache.loggers.RefreshLogger.create())

      val configuredRepos = mill.util.Jvm.reposFromStrings(millRepositories).get
      val jvmCache = JvmCache()
        .withArchiveCache(ArchiveCache().withCache(coursierCache0))
        .withIndex(
          JvmIndex.load(
            cache = coursierCache0,
            repositories = configuredRepos ++ Resolve.defaultRepositories,
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
