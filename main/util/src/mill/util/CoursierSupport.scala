package mill.util

import coursier.cache.{CacheLogger, FileCache}
import coursier.error.FetchError.DownloadingArtifacts
import coursier.error.ResolutionError.CantDownloadModule
import coursier.params.ResolutionParams
import coursier.parse.RepositoryParser
import coursier.util.Task
import coursier.{Artifacts, Classifier, Dependency, Repository, Resolution, Resolve, Type}
import mill.api.Loose.Agg
import mill.api.{Ctx, PathRef, Result}

import scala.collection.mutable
import scala.util.chaining.scalaUtilChainingOps

trait CoursierSupport {
  import CoursierSupport._

  private def coursierCache(
      ctx: Option[mill.api.Ctx.Log],
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]]
  ) =
    FileCache[Task]()
      .pipe { cache =>
        coursierCacheCustomizer.fold(cache)(c => c.apply(cache))
      }
      .pipe { cache =>
        ctx.fold(cache)(c => cache.withLogger(new TickerResolutionLogger(c)))
      }

  /**
   * Resolve dependencies using Coursier.
   *
   * We do not bother breaking this out into the separate ZincWorkerApi classpath,
   * because Coursier is already bundled with mill/Ammonite to support the
   * `import $ivy` syntax.
   */
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      sources: Boolean = false,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      resolveFilter: os.Path => Boolean = _ => true,
      artifactTypes: Option[Set[Type]] = None
  ): Result[Agg[PathRef]] = {
    def isLocalTestDep(dep: Dependency): Option[Seq[PathRef]] = {
      val org = dep.module.organization.value
      val name = dep.module.name.value
      val classpathKey = s"$org-$name"

      val classpathResourceText =
        try Some(os.read(
            os.resource(getClass.getClassLoader) / "mill/local-test-overrides" / classpathKey
          ))
        catch { case e: os.ResourceNotFoundException => None }

      classpathResourceText.map(_.linesIterator.map(s => PathRef(os.Path(s))).toSeq)
    }

    val (localTestDeps, remoteDeps) = deps.iterator.toSeq.partitionMap(d =>
      isLocalTestDep(d) match {
        case None => Right(d)
        case Some(vs) => Left(vs)
      }
    )

    val resolutionRes = resolveDependenciesMetadataSafe(
      repositories,
      remoteDeps,
      force,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer
    )

    resolutionRes.flatMap { resolution =>
      val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer)

      val artifactsResultOrError = Artifacts(coursierCache0)
        .withResolution(resolution)
        .withClassifiers(
          if (sources) Set(Classifier("sources"))
          else Set.empty
        )
        .withArtifactTypesOpt(artifactTypes)
        .eitherResult()

      artifactsResultOrError match {
        case Left(error: DownloadingArtifacts) =>
          val errorDetails = error.errors
            .map(_._2)
            .map(e => s"${System.lineSeparator()}  ${e.describe}")
            .mkString
          Result.Failure(
            s"Failed to load ${if (sources) "source " else ""}dependencies" + errorDetails
          )
        case Left(error) =>
          Result.Exception(error, new Result.OuterStack((new Exception).getStackTrace))
        case Right(res) =>
          Result.Success(
            Agg.from(
              res.files
                .map(os.Path(_))
                .filter(resolveFilter)
                .map(PathRef(_, quick = true))
            ) ++ localTestDeps.flatten
          )
      }
    }
  }

  @deprecated("Use the override accepting artifactTypes", "Mill after 0.12.0-RC3")
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      sources: Boolean,
      mapDependencies: Option[Dependency => Dependency],
      customizer: Option[Resolution => Resolution],
      ctx: Option[mill.api.Ctx.Log],
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]],
      resolveFilter: os.Path => Boolean
  ): Result[Agg[PathRef]] =
    resolveDependencies(
      repositories,
      deps,
      force,
      sources,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      resolveFilter
    )

  @deprecated(
    "Prefer resolveDependenciesMetadataSafe instead, which returns a Result instead of throwing exceptions",
    "0.12.0"
  )
  def resolveDependenciesMetadata(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None
  ): (Seq[Dependency], Resolution) = {
    val deps0 = deps.iterator.toSeq
    val res = resolveDependenciesMetadataSafe(
      repositories,
      deps0,
      force,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer
    )
    (deps0, res.getOrThrow)
  }

  def resolveDependenciesMetadataSafe(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None
  ): Result[Resolution] = {

    val rootDeps = deps.iterator
      .map(d => mapDependencies.fold(d)(_.apply(d)))
      .toSeq

    val forceVersions = force.iterator
      .map(mapDependencies.getOrElse(identity[Dependency](_)))
      .map { d => d.module -> d.version }
      .toMap

    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer)

    val resolutionParams = ResolutionParams()
      .withForceVersion(forceVersions)

    val resolve = Resolve()
      .withCache(coursierCache0)
      .withDependencies(rootDeps)
      .withRepositories(repositories)
      .withResolutionParams(resolutionParams)
      .withMapDependenciesOpt(mapDependencies)

    resolve.either() match {
      case Left(error) =>
        val cantDownloadErrors = error.errors.collect {
          case cantDownload: CantDownloadModule => cantDownload
        }
        if (error.errors.length == cantDownloadErrors.length) {
          val header =
            s"""|
                |Resolution failed for ${cantDownloadErrors.length} modules:
                |--------------------------------------------
                |""".stripMargin

          val helpMessage =
            s"""|
                |--------------------------------------------
                |
                |For additional information on library dependencies, see the docs at
                |${mill.api.BuildInfo.millDocUrl}/mill/Library_Dependencies.html""".stripMargin

          val errLines = cantDownloadErrors
            .map { err =>
              s"  ${err.module.trim}:${err.version} \n\t" +
                err.perRepositoryErrors.mkString("\n\t")
            }
            .mkString("\n")
          val msg = header + errLines + "\n" + helpMessage + "\n"
          Result.Failure(msg)
        } else
          Result.Exception(error, new Result.OuterStack((new Exception).getStackTrace))
      case Right(resolution0) =>
        val resolution = customizer.fold(resolution0)(_.apply(resolution0))
        Result.Success(resolution)
    }
  }

}

object CoursierSupport {

  /**
   * A Coursier Cache.Logger implementation that updates the ticker with the count and
   * overall byte size of artifacts being downloaded.
   *
   * In practice, this ticker output gets prefixed with the current target for which
   * dependencies are being resolved, using a [[mill.util.ProxyLogger]] subclass.
   */
  private[CoursierSupport] class TickerResolutionLogger(ctx: Ctx.Log) extends CacheLogger {
    private[CoursierSupport] case class DownloadState(var current: Long, var total: Long)

    private[CoursierSupport] var downloads = new mutable.TreeMap[String, DownloadState]()
    private[CoursierSupport] var totalDownloadCount = 0
    private[CoursierSupport] var finishedCount = 0
    private[CoursierSupport] var finishedState = DownloadState(0, 0)

    def updateTicker(): Unit = {
      val sums = downloads.values
        .fold(DownloadState(0, 0)) {
          (s1, s2) =>
            DownloadState(
              s1.current + s2.current,
              Math.max(s1.current, s1.total) + Math.max(s2.current, s2.total)
            )
        }
      sums.current += finishedState.current
      sums.total += finishedState.total
      ctx.log.ticker(
        s"Downloading [${downloads.size + finishedCount}/$totalDownloadCount] artifacts (~${sums.current}/${sums.total} bytes)"
      )
    }

    override def downloadingArtifact(url: String): Unit = synchronized {
      totalDownloadCount += 1
      downloads += url -> DownloadState(0, 0)
      updateTicker()
    }

    override def downloadLength(
        url: String,
        totalLength: Long,
        alreadyDownloaded: Long,
        watching: Boolean
    ): Unit = synchronized {
      val state = downloads(url)
      state.current = alreadyDownloaded
      state.total = totalLength
      updateTicker()
    }

    override def downloadProgress(url: String, downloaded: Long): Unit = synchronized {
      val state = downloads(url)
      state.current = downloaded
      updateTicker()
    }

    override def downloadedArtifact(url: String, success: Boolean): Unit = synchronized {
      val state = downloads(url)
      finishedState.current += state.current
      finishedState.total += Math.max(state.current, state.total)
      finishedCount += 1
      downloads -= url
      updateTicker()
    }
  }

  // Parse a list of repositories from their string representation
  def repoFromString(str: String, origin: String): Result[Seq[Repository]] = {
    val spaceSep = "\\s+".r

    val repoList =
      if (spaceSep.findFirstIn(str).isEmpty)
        str
          .split('|')
          .toSeq
          .filter(_.nonEmpty)
      else
        spaceSep
          .split(str)
          .toSeq
          .filter(_.nonEmpty)

    RepositoryParser.repositories(repoList).either match {
      case Left(errs) =>
        val msg =
          s"Invalid repository string in $origin:" + System.lineSeparator() +
            errs.map("  " + _ + System.lineSeparator()).mkString
        Result.Failure(msg, Some(Seq()))
      case Right(repos) =>
        Result.Success(repos)
    }
  }

}
