package mill.javalib

import mainargs.Flag
import mill.*
import mill.api.{PathRef, Task, *}
import mill.api.daemon.internal.{CompileProblemReporter, internal}
import mill.javalib.CoursierModule.Resolver
import mill.javalib.api.JvmWorkerUtil.isBinaryBridgeAvailable
import mill.javalib.api.internal.InternalJvmWorkerApi
import mill.javalib.api.{CompilationResult, JvmWorkerApi, JvmWorkerArgs, JvmWorkerUtil, Versions}
import mill.javalib.api.internal.ZincCompilerBridgeProvider

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

  /**
   * This is actually the testrunner classpath, not the scalalib classpath,
   * but the wrong name is preserved for backwards compatibility
   */
  def scalalibClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-testrunner")
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

  /** Whether Zinc debug logging is enabled. */
  def zincLogDebug: T[Boolean] = Task.Input(Task.ctx().log.debugEnabled)

  /** Whether to use file-based locking instead of PID-based locking. */
  def useFileLocks: T[Boolean] = Task.Input(Task.ctx().useFileLocks)

  def worker: Worker[JvmWorkerApi] = Task.Worker {
    // don't know why we have `worker` and `internalWorker`,
    // but we can't share the same instance, as we risk to run `close` on one,
    // while the other is still in use, hence the delegating facade.
    val internal = internalWorker()
    // just forward everything to `internalWorker`
    new JvmWorkerApi {
      override def compileJava(
          upstreamCompileOutput: Seq[CompilationResult],
          sources: Seq[os.Path],
          compileClasspath: Seq[os.Path],
          javaHome: Option[os.Path],
          javacOptions: Seq[String],
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean,
          incrementalCompilation: Boolean,
          workDir: os.Path
      )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] =
        internal.compileJava(
          upstreamCompileOutput,
          sources,
          compileClasspath,
          javaHome,
          javacOptions,
          reporter,
          reportCachedProblems,
          incrementalCompilation,
          workDir
        )

      override def compileMixed(
          upstreamCompileOutput: Seq[CompilationResult],
          sources: Seq[os.Path],
          compileClasspath: Seq[os.Path],
          javaHome: Option[os.Path],
          javacOptions: Seq[String],
          scalaVersion: String,
          scalaOrganization: String,
          scalacOptions: Seq[String],
          compilerClasspath: Seq[PathRef],
          scalacPluginClasspath: Seq[PathRef],
          compilerBridgeOpt: Option[PathRef],
          reporter: Option[CompileProblemReporter],
          reportCachedProblems: Boolean,
          incrementalCompilation: Boolean,
          auxiliaryClassFileExtensions: Seq[String],
          workDir: os.Path
      )(using ctx: JvmWorkerApi.Ctx): Result[CompilationResult] =
        internal.compileMixed(
          upstreamCompileOutput = upstreamCompileOutput,
          sources = sources,
          compileClasspath = compileClasspath,
          javaHome = javaHome,
          javacOptions = javacOptions,
          scalaVersion = scalaVersion,
          scalaOrganization = scalaOrganization,
          scalacOptions = scalacOptions,
          compilerClasspath = compilerClasspath,
          scalacPluginClasspath = scalacPluginClasspath,
          compilerBridgeOpt = compilerBridgeOpt,
          reporter = reporter,
          reportCachedProblems = reportCachedProblems,
          incrementalCompilation = incrementalCompilation,
          auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
          workDir = workDir
        )

      override def docJar(
          scalaVersion: String,
          scalaOrganization: String,
          compilerClasspath: Seq[PathRef],
          scalacPluginClasspath: Seq[PathRef],
          compilerBridgeOpt: Option[PathRef],
          javaHome: Option[os.Path],
          args: Seq[String],
          workDir: os.Path
      )(using ctx: JvmWorkerApi.Ctx): Boolean =
        internal.docJar(
          scalaVersion = scalaVersion,
          scalaOrganization = scalaOrganization,
          compilerClasspath = compilerClasspath,
          scalacPluginClasspath = scalacPluginClasspath,
          compilerBridgeOpt = compilerBridgeOpt,
          javaHome = javaHome,
          args = args,
          workDir = workDir
        )

    }
  }

  def internalWorkerClassLoader: Worker[ClassLoader & AutoCloseable] = Task.Worker {
    mill.util.Jvm.createClassLoader(classpath().map(_.path), getClass.getClassLoader)
  }

  @internal def internalWorker: Worker[InternalJvmWorkerApi] = Task.Worker {
    val ctx = Task.ctx()
    val jobs = ctx.jobs

    val cl = internalWorkerClassLoader()

    val zincCompilerBridge = ZincCompilerBridgeProvider(
      workspace = ctx.dest,
      logInfo = ctx.log.info,
      acquire = (scalaVersion, scalaOrganization) =>
        scalaCompilerBridgeJarV2(scalaVersion, scalaOrganization, defaultResolver()).map(_.path)
    )

    val args = JvmWorkerArgs(
      zincCompilerBridge,
      classPath = classpath().map(_.path),
      jobs = jobs,
      zincLogDebug = zincLogDebug(),
      useFileLocks = useFileLocks(),
      close0 = () => ()
    )

    cl.loadClass("mill.javalib.worker.JvmWorkerImpl")
      .getConstructor(classOf[JvmWorkerArgs])
      .newInstance(args)
      .asInstanceOf[InternalJvmWorkerApi]
  }

  private[mill] def scalaCompilerBridgeJarV2(
      scalaVersion: String,
      scalaOrganization: String,
      resolver: Resolver
  )(using ctx: TaskCtx): ZincCompilerBridgeProvider.AcquireResult[PathRef] = {
    val (bridgeDepStr, bridgeName, bridgeVersion) =
      JvmWorkerUtil.scalaCompilerBridgeDep(scalaVersion, scalaOrganization)

    val bridgeDep = Dep.parse(bridgeDepStr)
    val useSources = !isBinaryBridgeAvailable(scalaVersion)

    val deps = resolver.classpath(
      Seq(bridgeDep.bindDep("", "", "")),
      sources = useSources,
      mapDependencies = Some(overrideScalaLibrary(scalaVersion, scalaOrganization))
    )

    val bridgeJar = JvmWorkerUtil.grepJar(deps, bridgeName, bridgeVersion, useSources)

    if (useSources) {
      val classpath = compilerInterfaceClasspath(scalaVersion, scalaOrganization, resolver)
      ZincCompilerBridgeProvider.AcquireResult.NotCompiled(classpath, bridgeJar)
    } else ZincCompilerBridgeProvider.AcquireResult.Compiled(bridgeJar)
  }

  @deprecated("This is an internal API that has been accidentally exposed.", "Mill 1.0.2")
  def scalaCompilerBridgeJar(
      scalaVersion: String,
      scalaOrganization: String,
      resolver: Resolver
  )(using ctx: TaskCtx): (Option[Seq[PathRef]], PathRef) =
    scalaCompilerBridgeJarV2(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      resolver
    ) match {
      case ZincCompilerBridgeProvider.AcquireResult.Compiled(bridgeJar) => (None, bridgeJar)
      case ZincCompilerBridgeProvider.AcquireResult.NotCompiled(classpath, bridgeSourcesJar) =>
        (Some(classpath), bridgeSourcesJar)
    }

  def compilerInterfaceClasspath(
      scalaVersion: String,
      scalaOrganization: String,
      resolver: Resolver
  )(using ctx: TaskCtx): Seq[PathRef] = {
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
    (super.prepareOffline(all)() ++ classpath()).distinct
  }

  // noinspection ScalaUnusedSymbol - Task.Command
  def prepareOfflineCompiler(scalaVersion: String, scalaOrganization: String): Command[Unit] =
    Task.Command {
      classpath()
      scalaCompilerBridgeJarV2(scalaVersion, scalaOrganization, defaultResolver())
      ()
    }

}
