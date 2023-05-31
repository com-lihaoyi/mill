package mill.scalalib

import scala.annotation.nowarn
import coursier.Repository
import mainargs.Flag
import mill.Agg
import mill._
import mill.api.{Ctx, FixSizedCache, KeyedLockedCache, PathRef, Result}
import mill.define.{ExternalModule, Discover}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.api.ZincWorkerUtil.{isBinaryBridgeAvailable, isDotty, isDottyOrScala3}
import mill.scalalib.api.{ZincWorkerApi, ZincWorkerUtil, Versions}
import mill.util.Util.millProjectModule

/**
 * A default implementation of [[ZincWorkerModule]]
 */
object ZincWorkerModule extends ExternalModule with ZincWorkerModule with CoursierModule {
  lazy val millDiscover = Discover[this.type]
}

/**
 * A module managing an in-memory Zinc Scala incremental compiler
 */
trait ZincWorkerModule extends mill.Module with OfflineSupportModule { self: CoursierModule =>

  def classpath: T[Agg[PathRef]] = T {
    millProjectModule("mill-scalalib-worker", repositoriesTask())
  }

  def scalalibClasspath: T[Agg[PathRef]] = T {
    millProjectModule("mill-scalalib", repositoriesTask())
  }

  def testrunnerEntrypointClasspath: T[Agg[PathRef]] = T {
    millProjectModule("mill-testrunner-entrypoint", repositoriesTask(), artifactSuffix = "")
  }

  def backgroundWrapperClasspath: T[Agg[PathRef]] = T {
    millProjectModule(
      "mill-scalalib-backgroundwrapper",
      repositoriesTask(),
      artifactSuffix = ""
    )
  }

  def zincLogDebug: T[Boolean] = T.input(T.ctx().log.debugEnabled)

  def worker: Worker[ZincWorkerApi] = T.worker {
    val ctx = T.ctx()
    val jobs = T.ctx() match {
      case j: Ctx.Jobs => j.jobs
      case _ => 1
    }
    val cl = mill.api.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).iterator.to(Vector),
      getClass.getClassLoader
    )

    val cls = cl.loadClass("mill.scalalib.worker.ZincWorkerImpl")
    val instance = cls.getConstructor(
      classOf[
        Either[
          (ZincWorkerApi.Ctx, (String, String) => (Option[Agg[PathRef]], PathRef)),
          String => PathRef
        ]
      ], // compilerBridge
      classOf[(Agg[PathRef], String) => PathRef], // libraryJarNameGrep
      classOf[(Agg[PathRef], String) => PathRef], // compilerJarNameGrep
      classOf[KeyedLockedCache[_]], // compilerCache
      classOf[Boolean], // compileToJar
      classOf[Boolean] // zincLogDebug
    )
      .newInstance(
        Left((
          T.ctx(),
          (x: String, y: String) =>
            scalaCompilerBridgeJar(x, y, repositoriesTask())
              .asSuccess
              .getOrElse(
                throw new Exception(s"Failed to load compiler bridge for $x $y")
              )
              .value
        )),
        ZincWorkerUtil.grepJar(_, "scala-library", _, sources = false),
        ZincWorkerUtil.grepJar(_, "scala-compiler", _, sources = false),
        new FixSizedCache(jobs),
        java.lang.Boolean.FALSE,
        java.lang.Boolean.valueOf(zincLogDebug())
      )
    instance.asInstanceOf[ZincWorkerApi]
  }

  def scalaCompilerBridgeJar(
      scalaVersion: String,
      scalaOrganization: String,
      repositories: Seq[Repository]
  ): Result[(Option[Agg[PathRef]], PathRef)] = {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion match {
      case _ => (scalaVersion, ZincWorkerUtil.scalaBinaryVersion(scalaVersion))
    }

    val (bridgeDep, bridgeName, bridgeVersion) =
      if (isDottyOrScala3(scalaVersion0)) {
        val org = scalaOrganization
        val name =
          if (isDotty(scalaVersion0)) "dotty-sbt-bridge"
          else "scala3-sbt-bridge"
        val version = scalaVersion
        (ivy"$org:$name:$version", name, version)
      } else if (ZincWorkerUtil.millCompilerBridgeScalaVersions.contains(scalaVersion0)) {
        val org = "com.lihaoyi"
        val name = s"mill-scala-compiler-bridge_$scalaVersion"
        val version = Versions.millCompilerBridgeVersion
        (ivy"$org:$name:$version", name, version)
      } else {
        val org = "org.scala-sbt"
        val name = "compiler-bridge"
        val version = Versions.zinc
        (
          ivy"$org:${name}_${scalaBinaryVersion0}:$version",
          s"${name}_$scalaBinaryVersion0",
          version
        )
      }

    val useSources = !isBinaryBridgeAvailable(scalaVersion)

    val bridgeJar = resolveDependencies(
      repositories,
      Seq(bridgeDep.bindDep("", "", "")),
      useSources,
      Some(overrideScalaLibrary(scalaVersion, scalaOrganization))
    ).map(deps =>
      ZincWorkerUtil.grepJar(deps, bridgeName, bridgeVersion, useSources)
    )

    if (useSources) {
      for {
        jar <- bridgeJar
        classpath <- compilerInterfaceClasspath(scalaVersion, scalaOrganization, repositories)
      } yield (Some(classpath), jar)
    } else {
      bridgeJar.map((None, _))
    }
  }

  def compilerInterfaceClasspath(
      scalaVersion: String,
      scalaOrganization: String,
      repositories: Seq[Repository]
  ): Result[Agg[PathRef]] = {
    resolveDependencies(
      repositories = repositories,
      deps = Seq(ivy"org.scala-sbt:compiler-interface:${Versions.zinc}".bindDep("", "", "")),
      // Since Zinc 1.4.0, the compiler-interface depends on the Scala library
      // We need to override it with the scalaVersion and scalaOrganization of the module
      mapDependencies = Some(overrideScalaLibrary(scalaVersion, scalaOrganization))
    )
  }

  def overrideScalaLibrary(
      scalaVersion: String,
      scalaOrganization: String
  )(dep: coursier.Dependency): coursier.Dependency = {
    if (dep.module.name.value == "scala-library") {
      dep.withModule(dep.module.withOrganization(coursier.Organization(scalaOrganization)))
        .withVersion(scalaVersion)
    } else dep
  }

  @nowarn("msg=pure expression does nothing")
  override def prepareOffline(all: Flag): Command[Unit] = T.command {
    super.prepareOffline(all)()
    classpath()
    ()
  }

  @nowarn("msg=pure expression does nothing")
  def prepareOfflineCompiler(scalaVersion: String, scalaOrganization: String): Command[Unit] =
    T.command {
      classpath()
      scalaCompilerBridgeJar(scalaVersion, scalaOrganization, repositoriesTask())
      ()
    }

}
