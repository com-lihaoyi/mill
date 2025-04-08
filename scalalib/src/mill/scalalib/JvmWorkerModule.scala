package mill.scalalib

import mainargs.Flag
import mill._
import mill.api.{Ctx, PathRef, Result}
import mill.define.{Discover, ExternalModule, Task}
import mill.scalalib.api.JvmWorkerUtil.{isBinaryBridgeAvailable, isDotty, isDottyOrScala3}
import mill.scalalib.api.{Versions, JvmWorkerApi, JvmWorkerUtil}
import mill.scalalib.CoursierModule.Resolver

/**
 * A default implementation of [[JvmWorkerModule]]
 */
object JvmWorkerModule extends ExternalModule with JvmWorkerModule with CoursierModule {
  lazy val millDiscover = Discover[this.type]
}

/**
 * A module managing an in-memory Zinc Scala incremental compiler
 */
trait JvmWorkerModule extends OfflineSupportModule with CoursierModule {
  def jvmId: mill.define.Target[String] = Task[String] { "" }

  def jvmIndexVersion: mill.define.Target[String] =
    mill.scalalib.api.Versions.coursierJvmIndexVersion

  def classpath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-scalalib-worker")
    ))
  }

  def scalalibClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-scalalib")
    ))
  }

  def testrunnerEntrypointClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-testrunner-entrypoint", artifactSuffix = "")
    ))
  }

  def backgroundWrapperClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-scalalib-backgroundwrapper", artifactSuffix = "")
    ))
  }

  def zincLogDebug: T[Boolean] = Task.Input(Task.ctx().log.debugEnabled)

  /**
   * Optional custom Java Home for the JvmWorker to use
   *
   * If this value is None, then the JvmWorker uses the same Java used to run
   * the current mill instance.
   */
  def javaHome: T[Option[PathRef]] = Task {
    Option(jvmId()).filter(_ != "").map { id =>
      val path = mill.util.Jvm.resolveJavaHome(
        id = id,
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(implicitly[mill.api.Ctx.Log]),
        jvmIndexVersion = jvmIndexVersion()
      ).get
      PathRef(path, quick = true)
    }
  }

  def worker: Worker[JvmWorkerApi] = Task.Worker {
    val jobs = Task.ctx().jobs

    val cl = mill.util.Jvm.createClassLoader(
      classpath().map(_.path).toSeq,
      getClass.getClassLoader
    )

    val cls = cl.loadClass("mill.scalalib.worker.JvmWorkerImpl")
    val instance = cls.getConstructor(
      classOf[
        Either[
          (JvmWorkerApi.Ctx, (String, String) => (Option[Seq[PathRef]], PathRef)),
          String => PathRef
        ]
      ], // compilerBridge
      classOf[Int], // jobs
      classOf[Boolean], // compileToJar
      classOf[Boolean], // zincLogDebug
      classOf[Option[PathRef]], // javaHome
      classOf[() => Unit]
    )
      .newInstance(
        Left((
          Task.ctx(),
          (x: String, y: String) =>
            scalaCompilerBridgeJar(x, y, defaultResolver())
        )),
        jobs,
        java.lang.Boolean.FALSE,
        java.lang.Boolean.valueOf(zincLogDebug()),
        javaHome(),
        () => cl.close()
      )
    instance.asInstanceOf[JvmWorkerApi]
  }

  def scalaCompilerBridgeJar(
      scalaVersion: String,
      scalaOrganization: String,
      resolver: Resolver
  )(implicit ctx: Ctx): (Option[Seq[PathRef]], PathRef) = {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion match {
      case _ => (scalaVersion, JvmWorkerUtil.scalaBinaryVersion(scalaVersion))
    }

    val (bridgeDep, bridgeName, bridgeVersion) =
      if (isDottyOrScala3(scalaVersion0)) {
        val org = scalaOrganization
        val name =
          if (isDotty(scalaVersion0)) "dotty-sbt-bridge"
          else "scala3-sbt-bridge"
        val version = scalaVersion
        (ivy"$org:$name:$version", name, version)
      } else if (JvmWorkerUtil.millCompilerBridgeScalaVersions.contains(scalaVersion0)) {
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

    val deps = resolver.classpath(
      Seq(bridgeDep.bindDep("", "", "")),
      sources = useSources,
      mapDependencies = Some(overrideScalaLibrary(scalaVersion, scalaOrganization))
    )

    val bridgeJar = JvmWorkerUtil.grepJar(deps, bridgeName, bridgeVersion, useSources)
    val classpathOpt = Option.when(useSources) {
      compilerInterfaceClasspath(scalaVersion, scalaOrganization, resolver)
    }

    (classpathOpt, bridgeJar)
  }

  def compilerInterfaceClasspath(
      scalaVersion: String,
      scalaOrganization: String,
      resolver: Resolver
  )(implicit ctx: Ctx): Seq[PathRef] = {
    resolver.classpath(
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
      scalaCompilerBridgeJar(scalaVersion, scalaOrganization, defaultResolver())
      ()
    }

}
