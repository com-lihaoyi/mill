package mill.util

import coursier.cache.{CacheLogger, FileCache}
import coursier.core.{ArtifactSource, BomDependency, Extension, Info, Module, Project, Publication}
import coursier.error.FetchError.DownloadingArtifacts
import coursier.error.ResolutionError.CantDownloadModule
import coursier.params.ResolutionParams
import coursier.parse.RepositoryParser
import coursier.jvm.{JvmCache, JvmChannel, JvmIndex, JavaHome}
import coursier.util.{Artifact, EitherT, Monad, Task}
import coursier.{Artifacts, Classifier, Dependency, Repository, Resolution, Resolve, Type}

import mill.api.{Ctx, PathRef, Result}

import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.util.chaining.scalaUtilChainingOps
import coursier.cache.ArchiveCache
import scala.util.control.NonFatal

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
   * A `coursier.Repository` that exposes modules with hard-coded artifact list
   *
   * Used in Mill tests. This exposes internal workers for example, so that these
   * come from the build and not from remote repositories or ~/.ivy2/local. See
   * `MillJavaModule#{testTransitiveDeps,writeLocalTestOverrides}` in the Mill build.
   */
  private final class TestOverridesRepo(root: os.ResourcePath) extends Repository {

    private val map = new ConcurrentHashMap[Module, Option[String]]

    private def listFor(mod: Module): Either[os.ResourceNotFoundException, String] = {

      def entryPath = root / s"${mod.organization.value}-${mod.name.value}"

      val inCacheOpt = Option(map.get(mod))

      inCacheOpt
        .getOrElse {

          val computedOpt =
            try Some(os.read(entryPath))
            catch {
              case _: os.ResourceNotFoundException =>
                None
            }
          val concurrentOpt = Option(map.putIfAbsent(mod, computedOpt))
          concurrentOpt.getOrElse(computedOpt)
        }
        .toRight {
          new os.ResourceNotFoundException(entryPath)
        }
    }

    def find[F[_]: Monad](
        module: Module,
        version: String,
        fetch: Repository.Fetch[F]
    ): EitherT[F, String, (ArtifactSource, Project)] =
      EitherT.fromEither[F] {
        listFor(module)
          .left.map(e => s"No test override found at ${e.path}")
          .map { _ =>
            val proj = Project(
              module,
              version,
              dependencies = Nil,
              configurations = Map.empty,
              parent = None,
              dependencyManagement = Nil,
              properties = Nil,
              profiles = Nil,
              versions = None,
              snapshotVersioning = None,
              packagingOpt = None,
              relocated = false,
              actualVersionOpt = None,
              publications = Nil,
              info = Info.empty
            )
            (this, proj)
          }
      }

    def artifacts(
        dependency: Dependency,
        project: Project,
        overrideClassifiers: Option[Seq[Classifier]]
    ): Seq[(Publication, Artifact)] =
      listFor(project.module)
        .toTry.get
        .linesIterator
        .map(os.Path(_))
        .filter(os.exists)
        .map { path =>
          val pub = Publication(
            if (path.last.endsWith(".jar")) path.last.stripSuffix(".jar") else path.last,
            Type.jar,
            Extension.jar,
            Classifier.empty
          )
          val art = Artifact(path.toNIO.toUri.toASCIIString)
          (pub, art)
        }
        .toSeq
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
      artifactTypes: Option[Set[Type]] = None,
      resolutionParams: ResolutionParams = ResolutionParams()
  ): Result[Seq[PathRef]] = {
    val resolutionRes = resolveDependenciesMetadataSafe(
      repositories,
      deps,
      force,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      resolutionParams
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
        case Right(res) =>
          Result.Success(
            res.files
              .map(os.Path(_))
              .map(PathRef(_, quick = true))
          )
      }
    }
  }

  def jvmIndex(
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None
  ): JvmIndex = {
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer)
    jvmIndex0(ctx, coursierCacheCustomizer).unsafeRun()(coursierCache0.ec)
  }

  def jvmIndex0(
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      jvmIndexVersion: String = "latest.release"
  ): Task[JvmIndex] = {
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer)
    JvmIndex.load(
      cache = coursierCache0, // the coursier.cache.Cache instance to use
      repositories = Resolve().repositories, // repositories to use
      indexChannel = JvmChannel.module(
        JvmChannel.centralModule(),
        version = jvmIndexVersion
      ) // use new indices published to Maven Central
    )
  }

  /**
   * Resolve java home using Coursier.
   *
   * The id string has format "$DISTRIBUTION:$VERSION". e.g. graalvm-community:23.0.0
   */
  def resolveJavaHome(
      id: String,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      jvmIndexVersion: String = "latest.release"
  ): Result[os.Path] = {
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer)
    val jvmCache = JvmCache()
      .withArchiveCache(
        ArchiveCache().withCache(coursierCache0)
      )
      .withIndex(jvmIndex0(ctx, coursierCacheCustomizer, jvmIndexVersion))
    val javaHome = JavaHome()
      .withCache(jvmCache)
    try {
      val file = javaHome.get(id).unsafeRun()(coursierCache0.ec)
      Result.Success(os.Path(file))
    } catch {
      case NonFatal(error) =>
        Result.Exception(error, new Result.OuterStack((new Exception).getStackTrace))
    }
  }

  def resolveDependenciesMetadataSafe(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      boms: IterableOnce[BomDependency] = Nil
  ): Result[Resolution] = {

    val rootDeps = deps.iterator
      .map(d => mapDependencies.fold(d)(_.apply(d)))
      .toSeq

    val forceVersions = force.iterator
      .map(mapDependencies.getOrElse(identity[Dependency](_)))
      .map { d => d.module -> d.version }
      .toMap

    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer)

    val resolutionParams0 = resolutionParams
      .addForceVersion(forceVersions.toSeq*)

    val testOverridesRepo =
      new TestOverridesRepo(os.resource(getClass.getClassLoader) / "mill/local-test-overrides")

    val resolve = Resolve()
      .withCache(coursierCache0)
      .withDependencies(rootDeps)
      .withRepositories(testOverridesRepo +: repositories)
      .withResolutionParams(resolutionParams0)
      .withMapDependenciesOpt(mapDependencies)
      .withBoms(boms.iterator.toSeq)

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
