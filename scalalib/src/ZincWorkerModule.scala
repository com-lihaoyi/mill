package mill.scalalib

import scala.annotation.nowarn
import coursier.Repository
import mainargs.Flag
import mill.Agg
import mill.T
import mill.api.{Ctx, FixSizedCache, KeyedLockedCache, Loose, PathRef, Result}
import mill.define.{Command, Discover, ExternalModule, Input, Target, Worker}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.api.Util.{isBinaryBridgeAvailable, isDotty, isDottyOrScala3}
import mill.scalalib.api.{ZincWorkerApi, ZincWorkerUtil}
import os.Path

object ZincWorkerModule extends ExternalModule with ZincWorkerModule with CoursierModule {
  lazy val millDiscover = Discover[this.type]
}

trait ZincWorkerModule extends mill.Module with OfflineSupportModule { self: CoursierModule =>

  def classpath: Target[Agg[PathRef]] = T {
    mill.modules.Util.millProjectModule(
      "MILL_SCALA_WORKER",
      "mill-scalalib-worker",
      repositoriesTask()
    )
  }

  def scalalibClasspath: Target[Agg[PathRef]] = T {
    mill.modules.Util.millProjectModule(
      "MILL_SCALA_LIB",
      "mill-scalalib",
      repositoriesTask()
    )
  }

  def backgroundWrapperClasspath: Target[Agg[PathRef]] = T {
    mill.modules.Util.millProjectModule(
      "MILL_BACKGROUNDWRAPPER",
      "mill-scalalib-backgroundwrapper",
      repositoriesTask(),
      artifactSuffix = ""
    )
  }

  def zincLogDebug: Input[Boolean] = T.input(T.ctx.log.debugEnabled)

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
          (ZincWorkerApi.Ctx, (String, String) => (Option[Array[os.Path]], os.Path)),
          String => os.Path
        ]
      ], // compilerBridge
      classOf[(Agg[os.Path], String) => os.Path], // libraryJarNameGrep
      classOf[(Agg[os.Path], String) => os.Path], // compilerJarNameGrep
      classOf[KeyedLockedCache[_]], // compilerCache
      classOf[Boolean], // compileToJar
      classOf[Boolean] // zincLogDebug
    )
      .newInstance(
        Left((
          T.ctx(),
          (x: String, y: String) =>
            scalaCompilerBridgeJar(x, y, repositoriesTask()).asSuccess.get.value
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
  ): Result[(Option[Array[Path]], Path)] = {
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
      } else {
        val org = "org.scala-sbt"
        val name = "compiler-bridge"
        val version = Versions.zinc
        (ivy"$org::$name:$version", s"${name}_$scalaBinaryVersion0", version)
      }
    val useSources = !isBinaryBridgeAvailable(scalaVersion)

    val bridgeJar = resolveDependencies(
      repositories,
      Lib.depToDependency(_, scalaVersion0),
      Seq(bridgeDep),
      useSources,
      Some(overrideScalaLibrary(scalaVersion, scalaOrganization))
    ).map(deps =>
      ZincWorkerUtil.grepJar(deps.map(_.path), bridgeName, bridgeVersion, useSources)
    )

    if (useSources) {
      for {
        jar <- bridgeJar
        classpath <- compilerInterfaceClasspath(scalaVersion, scalaOrganization, repositories)
      } yield (Some(classpath.map(_.path).iterator.toArray), jar)
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
      depToDependency = Lib.depToDependency(_, scalaVersion, ""),
      deps = Seq(ivy"org.scala-sbt:compiler-interface:${Versions.zinc}"),
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
