package mill.util

import coursier.cache.ArtifactError
import coursier.parse.RepositoryParser
import coursier.util.{Gather, Task}
import coursier.{Dependency, Repository, Resolution}
import mill.api.{Ctx, PathRef, Result}
import mill.api.Loose.Agg
import java.io.File
import java.nio.file.NoSuchFileException
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

trait CoursierSupport {
  import CoursierSupport._

  private val CoursierRetryCount = 5
  private val CoursierRetryWait = 100

  /**
   * Somewhat generic way to retry some action and a Workaround for https://github.com/com-lihaoyi/mill/issues/1028
   *
   * Specifically build for coursier API interactions, which is known to have some concurrency issues which we handle on a known case basis.
   *
   * @param retryCount        The max retry count
   * @param ctx               The context to use ot show log messages (if defined)
   * @param errorMsgExtractor A generic way to get the error message of a run of `f`
   * @param f                 The actual operation to retry, if it results in a known concurrency error
   * @tparam T The result type of the computation
   * @return The result of the computation. If the computation was retries and finally succeeded, proviously occured errors will not be included in the result.
   */
  @tailrec
  private def retry[T](
      retryCount: Int = CoursierRetryCount,
      ctx: Option[Ctx.Log],
      errorMsgExtractor: T => Seq[String]
  )(f: () => T): T = {
    val tried = Try(f())
    tried match {
      case Failure(e: NoSuchFileException)
          if retryCount > 0 && e.getMessage.contains("__sha1.computed") =>
        // this one is not detected by coursier itself, so we try-catch handle it
        // I assume, this happens when another coursier thread already moved or rename dthe temporary file
        ctx.foreach(_.log.debug(
          s"Detected a concurrent download issue in coursier. Attempting a retry (${retryCount} left)"
        ))
        Thread.sleep(CoursierRetryWait)
        retry(retryCount - 1, ctx, errorMsgExtractor)(f)
      case Success(res) if retryCount > 0 =>
        val errors = errorMsgExtractor(res)
        if (errors.exists(e => e.contains("concurrent download"))) {
          ctx.foreach(_.log.debug(
            s"Detected a concurrent download issue in coursier. Attempting a retry (${retryCount} left)"
          ))
          Thread.sleep(CoursierRetryWait)
          retry(retryCount - 1, ctx, errorMsgExtractor)(f)
        } else if (errors.exists(e => e.contains("checksum not found"))) {
          ctx.foreach(_.log.debug(
            s"Detected a checksum download issue in coursier. Attempting a retry (${retryCount} left)"
          ))
          Thread.sleep(CoursierRetryWait)
          retry(retryCount - 1, ctx, errorMsgExtractor)(f)
        } else if (errors.exists(e => e.contains("download error"))) {
          ctx.foreach(_.log.debug(
            s"Detected a download error by coursier. Attempting a retry (${retryCount} left)"
          ))
          Thread.sleep(CoursierRetryWait)
          retry(retryCount - 1, ctx, errorMsgExtractor)(f)
        } else res
      case r => r.get
    }
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
      deps: IterableOnce[coursier.Dependency],
      force: IterableOnce[coursier.Dependency],
      sources: Boolean = false,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None,
      resolveFilter: os.Path => Boolean = _ => true
  ): Result[Agg[PathRef]] = {
    def isLocalTestDep(dep: coursier.Dependency): Option[Seq[PathRef]] = {
      val org = dep.module.organization.value
      val name = dep.module.name.value
      val classpathKey = s"$org-$name"

      val classpathResourceText =
        try Some(os.read(
            os.resource(getClass.getClassLoader) / "mill" / "local-test-overrides" / classpathKey
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

    val (_, resolution) = resolveDependenciesMetadata(
      repositories,
      remoteDeps,
      force,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer
    )
    val errs = resolution.errors

    if (errs.nonEmpty) {
      val header =
        s"""|
            |Resolution failed for ${errs.length} modules:
            |--------------------------------------------
            |""".stripMargin

      val helpMessage =
        s"""|
            |--------------------------------------------
            |
            |For additional information on library dependencies, see the docs at
            |${mill.api.BuildInfo.millDocUrl}/mill/Library_Dependencies.html""".stripMargin

      val errLines = errs.map {
        case ((module, vsn), errMsgs) => s"  ${module.trim}:$vsn \n\t" + errMsgs.mkString("\n\t")
      }.mkString("\n")
      val msg = header + errLines + "\n" + helpMessage + "\n"
      Result.Failure(msg)
    } else {

      val coursierCache0 = coursier.cache.FileCache[Task]().noCredentials
      val coursierCache = coursierCacheCustomizer.getOrElse(
        identity[coursier.cache.FileCache[Task]](_)
      ).apply(coursierCache0)

      def load(artifacts: Seq[coursier.util.Artifact]): (Seq[ArtifactError], Seq[File]) = {
        import scala.concurrent.ExecutionContext.Implicits.global
        val loadedArtifacts = Gather[Task].gather(
          for (a <- artifacts)
            yield coursierCache.file(a).run.map(a.optional -> _)
        ).unsafeRun()

        val errors = loadedArtifacts.collect {
          case (false, Left(x)) => x
          case (true, Left(x)) if !x.notFound => x
        }
        val successes = loadedArtifacts.collect { case (_, Right(x)) => x }

        (errors, successes)
      }

      val sourceOrJar =
        if (sources) {
          resolution.artifacts(
            types = Set(coursier.Type.source, coursier.Type.javaSource),
            classifiers = Some(Seq(coursier.Classifier("sources")))
          )
        } else resolution.artifacts(
          types = Set(
            coursier.Type.jar,
            coursier.Type.testJar,
            coursier.Type.bundle,
            coursier.Type("orbit"),
            coursier.Type("eclipse-plugin"),
            coursier.Type("maven-plugin")
          )
        )

      val (errors, successes) = retry(
        ctx = ctx,
        errorMsgExtractor = (res: (Seq[ArtifactError], Seq[File])) => res._1.map(_.describe)
      ) {
        () => load(sourceOrJar)
      }

      if (errors.isEmpty) {
        Result.Success(
          Agg.from(
            successes
              .map(os.Path(_))
              .filter(path => path.ext == "jar" && resolveFilter(path))
              .map(PathRef(_, quick = true))
          ) ++ localTestDeps.flatten
        )
      } else {
        val errorDetails = errors.map(e => s"${System.lineSeparator()}  ${e.describe}").mkString
        Result.Failure(
          s"Failed to load ${if (sources) "source " else ""}dependencies" + errorDetails
        )
      }
    }
  }

  def resolveDependenciesMetadata(
      repositories: Seq[Repository],
      deps: IterableOnce[coursier.Dependency],
      force: IterableOnce[coursier.Dependency],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None
  ): (Seq[Dependency], Resolution) = {

    val cachePolicies = coursier.cache.CacheDefaults.cachePolicies

    val forceVersions = force.iterator
      .map(mapDependencies.getOrElse(identity[Dependency](_)))
      .map { d => d.module -> d.version }
      .toMap

    val start0 = Resolution()
      .withRootDependencies(
        deps.iterator.map(mapDependencies.getOrElse(identity[Dependency](_))).toSeq
      )
      .withForceVersions(forceVersions)
      .withMapDependencies(mapDependencies)

    val start = customizer.getOrElse(identity[Resolution](_)).apply(start0)

    val resolutionLogger = ctx.map(c => new TickerResolutionLogger(c))
    val coursierCache0 = resolutionLogger match {
      case None => coursier.cache.FileCache[Task]().withCachePolicies(cachePolicies)
      case Some(l) =>
        coursier.cache.FileCache[Task]()
          .withCachePolicies(cachePolicies)
          .withLogger(l)
    }
    val coursierCache = coursierCacheCustomizer.getOrElse(
      identity[coursier.cache.FileCache[Task]](_)
    ).apply(coursierCache0)

    val fetches = coursierCache.fetchs

    val fetch = coursier.core.ResolutionProcess.fetch(repositories, fetches.head, fetches.tail)

    import scala.concurrent.ExecutionContext.Implicits.global

    val resolution =
      retry(ctx = ctx, errorMsgExtractor = (r: Resolution) => r.errors.flatMap(_._2)) {
        () => start.process.run(fetch).unsafeRun()
      }

    (deps.iterator.to(Seq), resolution)
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
  private[CoursierSupport] class TickerResolutionLogger(ctx: Ctx.Log)
      extends coursier.cache.CacheLogger {
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
