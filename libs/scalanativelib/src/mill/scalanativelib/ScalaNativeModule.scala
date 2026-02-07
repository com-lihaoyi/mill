package mill
package scalanativelib

import mainargs.Flag
import mill.{api as _, *}
import mill.api.{CrossVersion, Result, TaskCtx}
import mill.api.daemon.internal.bsp.ScalaBuildTarget
import mill.javalib.api.JvmWorkerUtil
import mill.api.daemon.internal.{ScalaNativeModuleApi, ScalaPlatform, internal}
import mill.javalib.RunModule
import mill.javalib.testrunner.{TestResult, TestRunner, TestRunnerUtils}
import mill.scalalib.{Dep, DepSyntax, Lib, SbtModule, ScalaModule, TestModule}
import mill.scalanativelib.api.*
import mill.scalanativelib.worker.{
  ScalaNativeWorker,
  ScalaNativeWorkerExternalModule,
  api as workerApi
}
import mill.scalanativelib.worker.api.ScalaNativeWorkerApi
import os.{Path, Shellable}

import java.lang

/**
 * Core configuration required to compile a single Scala-Native module
 */
trait ScalaNativeModule extends ScalaModule with ScalaNativeModuleApi { outer =>
  def scalaNativeVersion: T[String]
  override def platformSuffix = s"_native${scalaNativeBinaryVersion()}"

  trait ScalaNativeTests extends ScalaTests with TestScalaNativeModule {
    override def scalaNativeVersion = outer.scalaNativeVersion()
    override def releaseMode: T[ReleaseMode] = Task { outer.releaseMode() }
    override def logLevel: T[NativeLogLevel] = outer.logLevel()
  }

  def scalaNativeBinaryVersion =
    Task { JvmWorkerUtil.scalaNativeBinaryVersion(scalaNativeVersion()) }

  def scalaNativeWorkerVersion =
    Task { JvmWorkerUtil.scalaNativeWorkerVersion(scalaNativeVersion()) }

  def scalaNativeWorkerClasspath: T[Seq[PathRef]] = Task {
    // Use the global jvmWorker's resolver rather than this module's resolver so
    // we don't incorrectly override the worker classpath's scala-library version
    jvmWorker().defaultResolver().classpath(Seq(
      Dep.millProjectModule(s"mill-libs-scalanativelib-worker-${scalaNativeWorkerVersion()}")
    ))
  }

  def toolsMvnDeps = Task {
    scalaNativeVersion() match {
      case v @ ("0.4.0" | "0.4.1") =>
        Result.Failure(s"Scala Native $v is not supported. Please update to 0.4.2+")
      case version =>
        // Workaround for https://github.com/com-lihaoyi/mill/issues/6780:
        // Scala Native `tools_3` 0.5.10 can crash during linking when
        // `SourceLevelDebuggingConfig` is enabled.
        //
        // Using the Scala 2.13 published toolchain artifacts avoids the issue and is
        // compatible with Mill's Scala 3 runtime (Scala 3 uses the 2.13 standard library).
        Result.Success(
          Seq(
            mvn"org.scala-native:tools_2.13:$version",
            mvn"org.scala-native:test-runner_2.13:$version"
          )
        )

    }
  }

  def nativeMvnDeps: T[Seq[Dep]] = Task {
    val scalaVersionSpecific = {
      val version =
        if (scalaNativeVersion().startsWith("0.4")) scalaNativeVersion()
        else s"${scalaVersion()}+${scalaNativeVersion()}"

      if (JvmWorkerUtil.isScala3(scalaVersion()))
        Seq(mvn"org.scala-native::scala3lib::$version")
      else Seq(mvn"org.scala-native::scalalib::$version")
    }

    Seq(
      mvn"org.scala-native::nativelib::${scalaNativeVersion()}",
      mvn"org.scala-native::javalib::${scalaNativeVersion()}",
      mvn"org.scala-native::auxlib::${scalaNativeVersion()}"
    ) ++ scalaVersionSpecific
  }

  override def scalaLibraryMvnDeps: T[Seq[Dep]] = Task {
    super.scalaLibraryMvnDeps().map(dep =>
      dep.copy(cross = dep.cross match {
        case c: CrossVersion.Constant => c.copy(platformed = false)
        case c: CrossVersion.Binary => c.copy(platformed = false)
        case c: CrossVersion.Full => c.copy(platformed = false)
      })
    )
  }

  /** Adds [[nativeMvnDeps]] as mandatory dependencies. */
  override def mandatoryMvnDeps = Task {
    super.mandatoryMvnDeps() ++ nativeMvnDeps()
  }

  def bridgeFullClassPath: T[Seq[PathRef]] = Task {
    scalaNativeWorkerClasspath() ++ defaultResolver().classpath(
      toolsMvnDeps().map(Lib.depToBoundDep(_, mill.util.BuildInfo.scalaVersion, ""))
    )
  }

  override def scalacPluginMvnDeps: T[Seq[Dep]] = Task {
    super.scalacPluginMvnDeps() ++ Seq(
      mvn"org.scala-native:::nscplugin:${scalaNativeVersion()}"
    )
  }

  def logLevel: T[NativeLogLevel] = Task { NativeLogLevel.Info }

  private def readEnvVariable[T](
      env: Map[String, String],
      envVariable: String,
      values: Seq[T],
      valueOf: T => String
  ): Result[Option[T]] = {
    env.get(envVariable) match {
      case Some(value) =>
        values.find(valueOf(_) == value) match {
          case None =>
            Result.Failure(
              s"$envVariable=$value is not valid. Allowed values are: [${values.map(valueOf).mkString(", ")}]"
            )
          case Some(value) => Result.Success(Some(value))
        }
      case None => Result.Success(None)
    }
  }

  protected def releaseModeInput: T[Option[ReleaseMode]] = Task.Input {
    readEnvVariable[ReleaseMode](Task.env, "SCALANATIVE_MODE", ReleaseMode.values, _.value)
  }

  def releaseMode: T[ReleaseMode] = Task {
    releaseModeInput().getOrElse(ReleaseMode.Debug)
  }

  def nativeWorkdir = Task { Task.dest }

  class ScalaNativeBridge(
      scalaNativeWorkerValue: ScalaNativeWorker,
      bridgeFullClassPathValue: Seq[PathRef]
  ) {
    def apply[T](block: ScalaNativeWorkerApi => T): T = {
      scalaNativeWorkerValue.withValue(bridgeFullClassPathValue) {
        bridge => block(bridge)
      }
    }
  }
  private[scalanativelib] def withScalaNativeBridge = Task.Anon {
    new ScalaNativeBridge(
      ScalaNativeWorkerExternalModule.scalaNativeWorker(),
      bridgeFullClassPath()
    )
  }
  // Location of the clang compiler
  def nativeClang: T[PathRef] = Task {
    PathRef(os.Path(withScalaNativeBridge.apply().apply(_.discoverClang())))
  }

  // Location of the clang++ compiler
  def nativeClangPP: T[PathRef] = Task {
    PathRef(os.Path(withScalaNativeBridge.apply().apply(_.discoverClangPP())))
  }

  // GC choice, either "none", "boehm", "immix" or "commix"
  protected def nativeGCInput: T[Option[String]] = Task.Input {
    Task.env.get("SCALANATIVE_GC")
  }

  def nativeGC = Task {
    nativeGCInput().getOrElse(withScalaNativeBridge.apply().apply(_.defaultGarbageCollector()))
  }

  def nativeTarget: T[Option[String]] = Task { None }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = Task {
    withScalaNativeBridge.apply().apply(_.discoverCompileOptions())
  }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = Task {
    withScalaNativeBridge.apply().apply(_.discoverLinkingOptions())
  }

  // Whether to link `@stub` methods, or ignore them
  def nativeLinkStubs: T[Boolean] = Task { false }

  /**
   * Shall the resource files be embedded in the resulting binary file? Allows
   *  the use of getClass().getResourceAsStream() on the included files. Will
   *  not embed files with certain extensions, including ".c", ".h", ".scala"
   *  and ".class".
   */
  def nativeEmbedResources: T[Boolean] = Task { false }

  /** Shall we use the incremental compilation? */
  def nativeIncrementalCompilation: T[Boolean] = Task { false }

  /** Shall linker dump intermediate NIR after every phase? */
  def nativeDump: T[Boolean] = Task { false }

  // The LTO mode to use used during a release build
  protected def nativeLTOInput: T[Option[LTO]] = Task.Input {
    readEnvVariable[LTO](Task.env, "SCALANATIVE_LTO", LTO.values, _.value)
  }

  def nativeLTO: T[LTO] = Task { nativeLTOInput().getOrElse(LTO.None) }

  // Shall we optimize the resulting NIR code?
  protected def nativeOptimizeInput: T[Option[Boolean]] = Task.Input {
    readEnvVariable[Boolean](Task.env, "SCALANATIVE_OPTIMIZE", Seq(true, false), _.toString)
  }

  def nativeOptimize: T[Boolean] = Task { nativeOptimizeInput().getOrElse(true) }

  /** Build target for current compilation */
  def nativeBuildTarget: T[BuildTarget] = Task { BuildTarget.Application }

  /**
   * Shall be compiled with multithreading support. If equal to `None` the
   *  toolchain would detect if program uses system threads - when not thrads
   *  are not used, the program would be linked without multihreading support.
   */
  def nativeMultithreading: T[Option[Boolean]] = Task { None }

  /**
   * List of service providers which shall be allowed in the final binary.
   * Example:
   *   Map("java.nio.file.spi.FileSystemProvider" -> Seq("my.lib.MyCustomFileSystem"))
   *   Makes the implementation for the FileSystemProvider trait in `my.lib.MyCustomFileSystem`
   *   included in the binary for reflective instantiation.
   */
  def nativeServiceProviders: T[Map[String, Seq[String]]] = Task { Map.empty[String, Seq[String]] }

  /**
   * Shall toolchain enable mechanism for generation for source level debugging
   *  metadata.
   */
  def nativeSourceLevelDebuggingConfig: T[SourceLevelDebuggingConfig] =
    Task { SourceLevelDebuggingConfig.Disabled }

  /**
   * Create a new config with given base artifact name.
   */
  def nativeBaseName: T[String] =
    Task { "out" }

  private def nativeConfig(mainClass: Option[String]): Task[NativeConfig] = Task.Anon {
    val classpath = runClasspath().map(_.path).filter(_.toIO.exists).toList
    withScalaNativeBridge.apply().apply(_.config(
      mainClass.map(Right(_)).getOrElse(finalMainClassOpt()),
      classpath.map(_.toIO),
      nativeWorkdir().toIO,
      nativeClang().path.toIO,
      nativeClangPP().path.toIO,
      nativeTarget(),
      nativeCompileOptions(),
      nativeLinkingOptions(),
      nativeGC(),
      nativeLinkStubs(),
      nativeLTO().value,
      releaseMode().value,
      nativeOptimize(),
      nativeEmbedResources(),
      nativeIncrementalCompilation(),
      nativeDump(),
      nativeMultithreading(),
      nativeServiceProviders(),
      toWorkerApi(logLevel()),
      toWorkerApi(nativeBuildTarget()),
      toWorkerApi(nativeSourceLevelDebuggingConfig()),
      nativeBaseName()
    )) match {
      case Right(config) => Result.Success(NativeConfig(config))
      case Left(error) => Result.Failure(error)
    }
  }

  private[scalanativelib] def toWorkerApi(logLevel: api.NativeLogLevel): workerApi.NativeLogLevel =
    logLevel match {
      case api.NativeLogLevel.Error => workerApi.NativeLogLevel.Error
      case api.NativeLogLevel.Warn => workerApi.NativeLogLevel.Warn
      case api.NativeLogLevel.Info => workerApi.NativeLogLevel.Info
      case api.NativeLogLevel.Debug => workerApi.NativeLogLevel.Debug
      case api.NativeLogLevel.Trace => workerApi.NativeLogLevel.Trace
    }

  private[scalanativelib] def toWorkerApi(buildTarget: api.BuildTarget): workerApi.BuildTarget =
    buildTarget match {
      case api.BuildTarget.Application => workerApi.BuildTarget.Application
      case api.BuildTarget.LibraryDynamic => workerApi.BuildTarget.LibraryDynamic
      case api.BuildTarget.LibraryStatic => workerApi.BuildTarget.LibraryStatic
    }

  private[scalanativelib] def toWorkerApi(
      sourceLevelDebuggingConfig: api.SourceLevelDebuggingConfig
  ): workerApi.SourceLevelDebuggingConfig =
    sourceLevelDebuggingConfig match {
      case enabled: api.SourceLevelDebuggingConfig.Enabled =>
        workerApi.SourceLevelDebuggingConfig(
          enabled = true,
          generateFunctionSourcePositions = enabled.generateFunctionSourcePositions,
          generateLocalVariables = enabled.generateLocalVariables,
          customSourceRoots = enabled.customSourceRoots.map(_.toNIO)
        )
      case api.SourceLevelDebuggingConfig.Disabled =>
        workerApi.SourceLevelDebuggingConfig(false, false, false, Nil)
    }

  // Generates native binary
  def nativeLink: T[PathRef] = Task {
    PathRef(os.Path(withScalaNativeBridge.apply().apply(_.nativeLink(
      nativeConfig(None)().config,
      Task.dest.toIO
    ))))
  }

  def nativeLinkOtherMain(mainClass: String) = Task.Anon {
    val dest = Task.dest / s"nativeLink-${mainClass.hashCode}"
    os.remove.all(dest)
    PathRef(os.Path(withScalaNativeBridge.apply().apply(_.nativeLink(
      nativeConfig(Some(mainClass))().config,
      Task.dest.toIO
    ))))
  }

  // Same as super.run, but kept for bincompat
  override def run(args: Task[Args] = Task.Anon(Args())) = Task.Command {
    runForkedTask(finalMainClass, args)()
  }

  override def runMain(mainClass: String, args: String*): Command[Unit] = Task.Command {
    nativeRunnerOtherMain(mainClass)().run(args = args)
  }

  private class NativeRunner(
      mainClassDefault: Either[String, String],
      nativeExe: PathRef,
      forkArgsDefault: Seq[String],
      forkEnvDefault: Map[String, String],
      propagateEnvDefault: Boolean = true
  ) extends RunModule.Runner {

    override def run(
        args: os.Shellable,
        mainClass: String = null,
        forkArgs: Seq[String] = null,
        forkEnv: Map[String, String] = null,
        workingDir: os.Path = null,
        useCpPassingJar: java.lang.Boolean = null,
        extraRunClasspath: Seq[os.Path] = Nil,
        background: Boolean = false,
        runBackgroundLogToConsole: Boolean = false,
        propagateEnv: java.lang.Boolean = null
    )(using ctx: TaskCtx): Unit = {

      Option(mainClass) match {
        case None => mainClassDefault.fold(Task.fail, identity)
        case Some(mc) if Right(mc) == mainClassDefault => mc
        case Some(mc) => Task.fail(
            s"This runner can not run main class '${mc}'. You need to use a dedicated runner for this main class."
          )
      }

      val cwd = Option(workingDir).getOrElse(ctx.dest)
      val mainArgs = args.value
      Option(forkArgs).getOrElse(forkArgsDefault)
      val env = Option(forkEnv).getOrElse(forkEnvDefault)
      val propEnv = Option(propagateEnv).getOrElse(propagateEnvDefault: java.lang.Boolean)
      val native = nativeExe.path.toString

      os.call(
        cmd = native +: mainArgs,
        env = (if (propEnv) ctx.env else Map()) ++ env,
        cwd = cwd,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }
  }

  override def runner: Task[RunModule.Runner] = Task.Anon {
    new NativeRunner(
      mainClassDefault = finalMainClassOpt(),
      nativeExe = nativeLink(),
      forkArgsDefault = forkArgs(),
      forkEnvDefault = allForkEnv(),
      propagateEnvDefault = propagateEnv()
    )
  }

  def nativeRunnerOtherMain(mainClass: String): Task[RunModule.Runner] = Task.Anon {
    new NativeRunner(
      mainClassDefault = Right(mainClass),
      nativeExe = nativeLinkOtherMain(mainClass)(),
      forkArgsDefault = forkArgs(),
      forkEnvDefault = allForkEnv(),
      propagateEnvDefault = propagateEnv()
    )
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon {
    Some((
      ScalaBuildTarget.dataKind,
      ScalaBuildTarget(
        scalaOrganization = scalaOrganization(),
        scalaVersion = scalaVersion(),
        scalaBinaryVersion = JvmWorkerUtil.scalaBinaryVersion(scalaVersion()),
        ScalaPlatform.Native,
        jars = scalaCompilerClasspath().map(_.path.toURI.toString).iterator.toSeq,
        jvmBuildTarget = None
      )
    ))
  }

  def coursierProject: Task[coursier.core.Project] = Task.Anon {

    // Exclude cross published version dependencies leading to conflicts in Scala 3 vs 2.13
    // When using Scala 3 exclude Scala 2.13 standard native libraries,
    // when using Scala 2.13 exclude Scala 3 standard native libraries
    // Use full name, Maven style published artifacts cannot use artifact/cross version for exclusion rules
    val nativeStandardLibraries =
      Seq("nativelib", "clib", "posixlib", "windowslib", "javalib", "auxlib")

    val scalaBinaryVersionToExclude = artifactScalaVersion() match {
      case "3" => "2.13" :: Nil
      case "2.13" => "3" :: Nil
      case _ => Nil
    }

    val nativeSuffix = platformSuffix()

    val exclusions = coursier.core.MinimizedExclusions(
      scalaBinaryVersionToExclude
        .flatMap { scalaBinVersion =>
          nativeStandardLibraries.map { library =>
            coursier.core.Organization("org.scala-native") ->
              coursier.core.ModuleName(s"$library${nativeSuffix}_$scalaBinVersion")
          }
        }
        .toSet
    )

    val proj = coursierProject0()

    proj.withDependencies(
      proj.dependencies.map {
        case (config, dep) =>
          config -> dep.withMinimizedExclusions(
            dep.minimizedExclusions.join(exclusions)
          )
      }
    )
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = {
    val tasks =
      if (all.value) Seq(
        scalaNativeWorkerClasspath,
        bridgeFullClassPath
      )
      else Seq()
    Task.Command {
      (
        super.prepareOffline(all)() ++
          Task.sequence(tasks)().flatten
      ).distinct
    }
  }

  override def zincAuxiliaryClassFileExtensions: T[Seq[String]] =
    super.zincAuxiliaryClassFileExtensions() :+ "nir"

}

trait TestScalaNativeModule extends ScalaNativeModule with TestModule {
  override def resources: T[Seq[PathRef]] = super[ScalaNativeModule].resources
  override def testLocal(args: String*): Command[(msg: String, results: Seq[TestResult])] =
    Task.Command { testForked(args*)() }
  override protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(msg: String, results: Seq[TestResult])] = Task.Anon {

    val (close, framework) = withScalaNativeBridge.apply().apply(_.getFramework(
      nativeLink().path.toIO,
      allForkEnv(),
      toWorkerApi(logLevel()),
      testFramework()
    ))

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Seq(compile().classes.path),
      args(),
      Task.testReporter,
      cls => TestRunnerUtils.globFilter(globSelectors())(cls.getName)
    )
    val res = TestModule.handleResults(doneMsg, results, Task.ctx(), testReportXml())
    // Hack to try and let the Scala Native subprocess finish streaming its stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }
  override def mvnDeps = super.mvnDeps() ++ Seq(
    mvn"org.scala-native::test-interface::${scalaNativeVersion()}"
  )
  override def mainClass: T[Option[String]] = Some("scala.scalanative.testinterface.TestMain")
}

trait SbtNativeModule extends ScalaNativeModule with SbtModule
