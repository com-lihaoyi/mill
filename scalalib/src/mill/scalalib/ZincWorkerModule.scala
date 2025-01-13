package mill.scalalib

import coursier.Repository
import mainargs.Flag
import mill._
import mill.api.{Ctx, PathRef, Result}
import mill.define.{Discover, ExternalModule, Task}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.api.ZincWorkerUtil.{isBinaryBridgeAvailable, isDotty, isDottyOrScala3}
import mill.scalalib.api.{Versions, ZincWorkerApi, ZincWorkerUtil}
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
trait ZincWorkerModule extends mill.Module with OfflineSupportModule with CoursierModule {
  def jvmId: mill.define.Target[String] = Task[String] { "" }

  def jvmIndexVersion: mill.define.Target[String] =
    mill.scalalib.api.Versions.coursierJvmIndexVersion

  def classpath: T[Agg[PathRef]] = Task {
    millProjectModule("mill-scalalib-worker", repositoriesTask())
  }

  def scalalibClasspath: T[Agg[PathRef]] = Task {
    millProjectModule("mill-scalalib", repositoriesTask())
  }

  def testrunnerEntrypointClasspath: T[Agg[PathRef]] = Task {
    millProjectModule("mill-testrunner-entrypoint", repositoriesTask(), artifactSuffix = "")
  }

  def backgroundWrapperClasspath: T[Agg[PathRef]] = Task {
    millProjectModule(
      "mill-scalalib-backgroundwrapper",
      repositoriesTask(),
      artifactSuffix = ""
    )
  }

  def zincLogDebug: T[Boolean] = Task.Input(T.ctx().log.debugEnabled)

  /**
   * Optional custom Java Home for the ZincWorker to use
   *
   * If this value is None, then the ZincWorker uses the same Java used to run
   * the current mill instance.
   */
  def javaHome: T[Option[PathRef]] = Task {
    Option(jvmId()).filter(_ != "").map { id =>
      val path = mill.util.Jvm.resolveJavaHome(
        id = id,
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(implicitly[mill.api.Ctx.Log]),
        jvmIndexVersion = jvmIndexVersion()
      ).getOrThrow
      PathRef(path, quick = true)
    }
  }

  def worker: Worker[ZincWorkerApi] = Task.Worker {
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
      classOf[Int], // jobs
      classOf[Boolean], // compileToJar
      classOf[Boolean], // zincLogDebug
      classOf[Option[PathRef]] // javaHome
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
        jobs,
        java.lang.Boolean.FALSE,
        java.lang.Boolean.valueOf(zincLogDebug()),
        javaHome()
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
      sources = useSources,
      mapDependencies = Some(overrideScalaLibrary(scalaVersion, scalaOrganization))
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

  override def prepareOffline(all: Flag): Command[Unit] = Task.Command {
    super.prepareOffline(all)()
    classpath()
    ()
  }

  def prepareOfflineCompiler(scalaVersion: String, scalaOrganization: String): Command[Unit] =
    Task.Command {
      classpath()
      scalaCompilerBridgeJar(scalaVersion, scalaOrganization, repositoriesTask())
      ()
    }

}
