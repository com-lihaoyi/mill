package mill.javalib

import mainargs.Flag
import mill.*
import mill.api.Result
import mill.api.{PathRef, TaskCtx}
import mill.api.{Discover, ExternalModule, Task}
import mill.javalib.api.JvmWorkerUtil.{isBinaryBridgeAvailable, isDotty, isDottyOrScala3}
import mill.javalib.api.{JvmWorkerApi, JvmWorkerUtil, Versions}
import mill.javalib.CoursierModule.Resolver
import mill.javalib.internal.{JvmWorkerArgs, JvmWorkerFactoryApi, ZincCompilerBridge}

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

  def classpath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-worker")
    ))
  }

  def scalalibClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib")
    ))
  }

  def testrunnerEntrypointClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-testrunner-entrypoint", artifactSuffix = "")
    ))
  }

  def backgroundWrapperClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-backgroundwrapper", artifactSuffix = "")
    ))
  }

  def zincLogDebug: T[Boolean] = Task.Input(Task.ctx().log.debugEnabled)

  def worker: Worker[JvmWorkerApi] = Task.Worker {
    val jobs = Task.ctx().jobs

    val cl = mill.util.Jvm.createClassLoader(
      classpath().map(_.path).toSeq,
      getClass.getClassLoader
    )

    val factory = cl.loadClass("mill.javalib.worker.JvmWorkerFactory").getConstructor().newInstance()
      .asInstanceOf[JvmWorkerFactoryApi]

    val args = JvmWorkerArgs(
      ZincCompilerBridge.Provider(Task.ctx(), (scalaVersion, scalaOrganization) => scalaCompilerBridgeJar(
        scalaVersion = scalaVersion, scalaOrganization = scalaOrganization, defaultResolver()
      )),
      jobs = jobs,
      compileToJar = false,
      zincLogDebug = zincLogDebug(),
      close0 = () => cl.close()
    )
    factory.make(args)
  }

  def scalaCompilerBridgeJar(
      scalaVersion: String,
      scalaOrganization: String,
      resolver: Resolver
  )(implicit ctx: TaskCtx): (Option[Seq[PathRef]], PathRef) = {
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
        (mvn"$org:$name:$version", name, version)
      } else if (JvmWorkerUtil.millCompilerBridgeScalaVersions.contains(scalaVersion0)) {
        val org = "com.lihaoyi"
        val name = s"mill-scala-compiler-bridge_$scalaVersion"
        val version = Versions.millCompilerBridgeVersion
        (mvn"$org:$name:$version", name, version)
      } else {
        val org = "org.scala-sbt"
        val name = "compiler-bridge"
        val version = Versions.zinc
        (
          mvn"$org:${name}_${scalaBinaryVersion0}:$version",
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
  )(implicit ctx: TaskCtx): Seq[PathRef] = {
    resolver.classpath(
      deps = Seq(mvn"org.scala-sbt:compiler-interface:${Versions.zinc}".bindDep("", "", "")),
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

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++ classpath()
    ).distinct
  }

  def prepareOfflineCompiler(scalaVersion: String, scalaOrganization: String): Command[Unit] =
    Task.Command {
      classpath()
      scalaCompilerBridgeJar(scalaVersion, scalaOrganization, defaultResolver())
      ()
    }

}
