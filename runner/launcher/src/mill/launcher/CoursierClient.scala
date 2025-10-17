package mill.launcher

import coursier.{Artifacts, Dependency, ModuleName, Organization, Resolve, VersionConstraint}
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.maven.MavenRepository
import coursier.util.Task
import coursier.core.Module

import java.io.File

import scala.concurrent.ExecutionContext.Implicits.global

object CoursierClient {
  def resolveMillDaemon() = {
    val coursierCache0 = FileCache[Task]()
      .withLogger(coursier.cache.loggers.RefreshLogger.create())

    val testOverridesRepos = Option(System.getenv("MILL_LOCAL_TEST_REPO"))
      .toSeq
      .flatMap(_.split(File.pathSeparator).toSeq)
      .map { path =>
        MavenRepository(os.Path(path).toURI.toASCIIString)
      }

    val artifactsResultOrError = {

      val resolve = Resolve()
        .withCache(coursierCache0)
        .withDependencies(Seq(Dependency(
          Module(Organization("com.lihaoyi"), ModuleName("mill-runner-daemon_3"), Map()),
          VersionConstraint(mill.client.BuildInfo.millVersion)
        )))
        .withRepositories(testOverridesRepos ++ Resolve.defaultRepositories)

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

    artifactsResultOrError.artifacts.map(_._2.toString).toArray
  }

  def resolveJavaHome(id: String): java.io.File = {
    val coursierCache0 = FileCache[Task]()
      .withLogger(coursier.cache.loggers.RefreshLogger.create())
    val jvmCache = JvmCache()
      .withArchiveCache(ArchiveCache().withCache(coursierCache0))
      .withIndex(
        JvmIndex.load(
          cache = coursierCache0,
          repositories = Resolve.defaultRepositories,
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

    coursierCache0.logger.using(javaHome.get(id)).unsafeRun()(using coursierCache0.ec)
  }
}
