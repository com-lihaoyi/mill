package mill.launcher

import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.util.Task
import coursier.{
  Artifacts,
  Classifier,
  Dependency,
  Fetch,
  Organization,
  ModuleName,
  VersionConstraint,
  Repository,
  Resolution,
  Resolve,
  Type
}
import coursier.cache.{ArchiveCache, CachePolicy, FileCache}
import coursier.core.{BomDependency, Module}
import coursier.error.FetchError.DownloadingArtifacts
import coursier.error.ResolutionError.CantDownloadModule
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.launcher.{BootstrapGenerator, ClassLoaderContent, ClassPathEntry, Parameters}
import coursier.params.ResolutionParams
import coursier.parse.RepositoryParser
import coursier.util.Task
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Using
import coursier.core.{ArtifactSource, Extension, Info, Module, Project, Publication}
import coursier.util.{Artifact, EitherT, Monad}
import coursier.{Classifier, Dependency, Repository, Type}

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
object CoursierClient {
  def millDaemonLauncher(isServer: Boolean): java.nio.file.Path = {
    val repositories = Await.result(Resolve().finalRepositories.future(), Duration.Inf)
    val coursierCache0 = FileCache[Task]()
      .withLogger(coursier.cache.loggers.RefreshLogger.create())
    val testOverridesClassloaders = System.getenv("MILL_LOCAL_TEST_OVERRIDE_CLASSPATH") match {
      case null => Nil
      case cp =>
        cp.split(';').map { s =>
          val url = os.Path(s).toNIO.toUri.toURL
          new java.net.URLClassLoader(Array(url))
        }.toList
    }
    val artifactsResultOrError =
      try {
        val envTestOverridesRepo = testOverridesClassloaders.map(cl =>
          new TestOverridesRepo(os.resource(cl) / "mill/local-test-overrides")
        )

        val resourceTestOverridesRepo =
          new TestOverridesRepo(os.resource(getClass.getClassLoader) / "mill/local-test-overrides")

        Fetch()
          .withCache(coursierCache0)
          .withDependencies(Seq(Dependency(
            Module(Organization("com.lihaoyi"), ModuleName("mill-runner-daemon_3"), Map()),
            VersionConstraint(mill.client.BuildInfo.millVersion)
          )))
          .withRepositories(Seq(resourceTestOverridesRepo) ++ envTestOverridesRepo ++ repositories)
          .eitherResult()
          .right.get

      } finally {
        testOverridesClassloaders.foreach(_.close())
      }

    val cp = artifactsResultOrError.artifacts.map(_._2).map(os.Path(_))
    val compilerInterfaceDeps = artifactsResultOrError.resolution.minDependencies
      .iterator
      .filter { dep =>
        dep.module.organization.value == "org.scala-sbt" && (
          dep.module.name.value == "compiler-interface" ||
            dep.module.name.value == "test-interface"
        )
      }
      .toSeq
    val coreApiDeps = artifactsResultOrError.resolution.minDependencies
      .iterator
      .filter { dep =>
        dep.module.organization.value == "com.lihaoyi" &&
        dep.module.name.value == "mill-core-api_3"
      }
      .toSeq

    def artifactsFor(deps: Seq[Dependency]) = {
      val subRes =
        artifactsResultOrError.resolution.subset0(deps).right.get
      Artifacts()
        .withResolution(subRes)
        .eitherResult()
        .right.get
        .artifacts
        .map(_._2)
        .map(os.Path(_))
    }
    val interfaceArtifacts = artifactsFor(compilerInterfaceDeps)
    val coreApiArtifacts = artifactsFor(compilerInterfaceDeps ++ coreApiDeps)

    val params = Parameters.Bootstrap(
      Seq(
        // putting compiler-interface and test-interface in a first loader
        // (used to interface with scalac instances and test frameworks)
        ClassLoaderContent(interfaceArtifacts.map(f =>
          ClassPathEntry.Url(f.toNIO.toUri.toASCIIString)
        )),
        // putting core-api right above (used to load builds)
        ClassLoaderContent(coreApiArtifacts.map(f =>
          ClassPathEntry.Url(f.toNIO.toUri.toASCIIString)
        )),
        // lastly, the remaning JARs
        ClassLoaderContent(cp.map(f =>
          ClassPathEntry.Url(f.toNIO.toUri.toASCIIString)
        ))
      ),
      mainClass = if (isServer) "mill.daemon.MillDaemonMain" else "mill.daemon.MillMain"
    )
    val launcher = os.temp(Array.emptyByteArray, prefix = "mill-", suffix = ".jar")
    BootstrapGenerator.generate(params, launcher.toNIO)

    launcher.toNIO
  }

  /** Files refered to by this launcher */
  def launcherEntries(launcher: java.nio.file.Path): Array[java.nio.file.Path] =
    Using.resource(new ZipFile(launcher.toFile)) { zf =>
      Iterator.from(0)
        .map(n => if (n == 0) "" else s"-$n")
        .map(suffix => s"coursier/bootstrap/launcher/bootstrap-jar-urls$suffix")
        .map(name => zf.getEntry(name))
        .takeWhile(_ != null)
        .map { entry =>
          Using.resource(zf.getInputStream(entry)) { is =>
            new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          }
        }
        .flatMap(_.linesIterator)
        .filter(_.nonEmpty)
        .map(new java.net.URI(_))
        .filter(_.getScheme == "file")
        .map(java.nio.file.Paths.get(_))
        .toArray
    }

  def resolveJavaHome(id: String): java.io.File = {
    val coursierCache0 = FileCache[Task]()
      .withLogger(coursier.cache.loggers.RefreshLogger.create())
    val jvmCache = JvmCache()
      .withArchiveCache(ArchiveCache().withCache(coursierCache0))
      .withIndex(
        JvmIndex.load(
          cache = coursierCache0,
          repositories = Resolve().repositories,
          indexChannel = JvmChannel.module(
            JvmChannel.centralModule(),
            version = mill.client.Versions.coursierJvmIndexVersion
          )
        )
      )

    val javaHome = JavaHome().withCache(jvmCache)
      // when given a version like "17", always pick highest version in the index
      // rather than the highest already on disk
      .withUpdate(true)

    javaHome.get(id).unsafeRun()(coursierCache0.ec)
  }
}

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
