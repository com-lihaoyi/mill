package mill.launcher

import coursier.{Artifacts, Dependency, ModuleName, Organization, Resolve, VersionConstraint}
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.util.Task
import coursier.core.Module

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import mill.coursierutil.TestOverridesRepo

object CoursierClient {

  /**
   * Resolves the classpath for the mill daemon.
   *
   * @param scalaVersion the version of the Scala to use. If not specified, the version that mill uses itself will be
   *                     used.
   */
  def resolveMillDaemon(scalaVersion: Option[String]): Array[String] = {
    val repositories = Await.result(Resolve().finalRepositories.future(), Duration.Inf)
    val coursierCache0 = FileCache[Task]()
      .withLogger(coursier.cache.loggers.RefreshLogger.create())

    val artifactsResult = {
      val resolve = Resolve()
        .withCache(coursierCache0)
        .withDependencies(Seq(
          Dependency(
            Module(
              Organization("com.lihaoyi"),
              ModuleName("mill-runner-daemon_3"),
              attributes = Map.empty
            ),
            VersionConstraint(mill.client.BuildInfo.millVersion)
          )
        ) ++ scalaVersion.map { version =>
          Dependency(
            Module(
              Organization("org.scala-lang"),
              ModuleName("scala3-compiler_3"),
              attributes = Map.empty
            ),
            VersionConstraint(version)
          )
        })
        .withRepositories(Seq(TestOverridesRepo) ++ repositories)

      resolve.either() match {
        case Left(err) => sys.error(err.toString)
        case Right(resolution) =>
          Artifacts(coursierCache0)
            .withResolution(resolution)
            .eitherResult()
            .right.get
      }
    }

    artifactsResult.artifacts.iterator.map { case (_, file) => file.toString }.toArray
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

    javaHome.get(id).unsafeRun()(using coursierCache0.ec)
  }
}
