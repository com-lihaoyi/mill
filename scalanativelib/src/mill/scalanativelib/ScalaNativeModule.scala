package mill
package scalanativelib

import mainargs.Flag
import mill.api.Loose.Agg
import mill.api.{Result, internal}
import mill.define.{Command, Task}
import mill.util.Jvm
import mill.util.Util.millProjectModule
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.bsp.{ScalaBuildTarget, ScalaPlatform}
import mill.scalalib.{
  BoundDep,
  CrossVersion,
  Dep,
  DepSyntax,
  Lib,
  SbtModule,
  ScalaModule,
  TestModule
}
import mill.testrunner.{TestResult, TestRunner, TestRunnerUtils}
import mill.scalanativelib.api._
import mill.scalanativelib.worker.{ScalaNativeWorkerExternalModule, api => workerApi}
import mill.T
import mill.api.PathRef
import mill.main.client.EnvVars

trait ScalaNativeModule extends ScalaModule { outer =>
  def scalaNativeVersion: T[String]
  override def platformSuffix = s"_native${scalaNativeBinaryVersion()}"

  @deprecated("use ScalaNativeTests", "0.11.0")
  type ScalaNativeModuleTests = ScalaNativeTests
  trait ScalaNativeTests extends ScalaTests with TestScalaNativeModule {
    override def scalaNativeVersion = outer.scalaNativeVersion()
    override def releaseMode: T[ReleaseMode] = Task { outer.releaseMode() }
    override def logLevel: T[NativeLogLevel] = outer.logLevel()
  }

  def scalaNativeBinaryVersion =
    Task { ZincWorkerUtil.scalaNativeBinaryVersion(scalaNativeVersion()) }

  def scalaNativeWorkerVersion =
    Task { ZincWorkerUtil.scalaNativeWorkerVersion(scalaNativeVersion()) }

  def scalaNativeWorkerClasspath = Task {
    millProjectModule(
      s"mill-scalanativelib-worker-${scalaNativeWorkerVersion()}",
      repositoriesTask(),
      resolveFilter = _.toString.contains("mill-scalanativelib-worker")
    )
  }

  def toolsIvyDeps = Task {
    scalaNativeVersion() match {
      case v @ ("0.4.0" | "0.4.1") =>
        Result.Failure(s"Scala Native $v is not supported. Please update to 0.4.2+")
      case version =>
        Result.Success(
          Agg(
            ivy"org.scala-native::tools:$version",
            ivy"org.scala-native::test-runner:$version"
          )
        )

    }
  }

  def nativeIvyDeps: T[Agg[Dep]] = Task {
    val scalaVersionSpecific = {
      val version =
        if (scalaNativeVersion().startsWith("0.4")) scalaNativeVersion()
        else s"${scalaVersion()}+${scalaNativeVersion()}"

      if (ZincWorkerUtil.isScala3(scalaVersion()))
        Agg(ivy"org.scala-native::scala3lib::$version")
      else Agg(ivy"org.scala-native::scalalib::$version")
    }

    Agg(
      ivy"org.scala-native::nativelib::${scalaNativeVersion()}",
      ivy"org.scala-native::javalib::${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib::${scalaNativeVersion()}"
    ) ++ scalaVersionSpecific
  }

  override def scalaLibraryIvyDeps: T[Agg[Dep]] = Task {
    super.scalaLibraryIvyDeps().map(dep =>
      dep.copy(cross = dep.cross match {
        case c: CrossVersion.Constant => c.copy(platformed = false)
        case c: CrossVersion.Binary => c.copy(platformed = false)
        case c: CrossVersion.Full => c.copy(platformed = false)
      })
    )
  }

  /** Adds [[nativeIvyDeps]] as mandatory dependencies. */
  override def mandatoryIvyDeps = Task {
    super.mandatoryIvyDeps() ++ nativeIvyDeps()
  }

  def bridgeFullClassPath: T[Agg[PathRef]] = Task {
    Lib.resolveDependencies(
      repositoriesTask(),
      toolsIvyDeps().map(Lib.depToBoundDep(_, mill.main.BuildInfo.scalaVersion, "")),
      ctx = Some(T.log)
    ).map(t => (scalaNativeWorkerClasspath() ++ t))
  }

  private[scalanativelib] def scalaNativeBridge = Task.Anon {
    ScalaNativeWorkerExternalModule.scalaNativeWorker().bridge(bridgeFullClassPath())
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = Task {
    super.scalacPluginIvyDeps() ++ Agg(
      ivy"org.scala-native:::nscplugin:${scalaNativeVersion()}"
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
    readEnvVariable[ReleaseMode](T.env, "SCALANATIVE_MODE", ReleaseMode.values, _.value)
  }

  def releaseMode: T[ReleaseMode] = Task {
    releaseModeInput().getOrElse(ReleaseMode.Debug)
  }

  def nativeWorkdir = Task { T.dest }

  // Location of the clang compiler
  def nativeClang = Task {
    os.Path(
      scalaNativeBridge().discoverClang()
    )
  }

  // Location of the clang++ compiler
  def nativeClangPP = Task {
    os.Path(
      scalaNativeBridge().discoverClangPP()
    )
  }

  // GC choice, either "none", "boehm", "immix" or "commix"
  protected def nativeGCInput: T[Option[String]] = Task.Input {
    T.env.get("SCALANATIVE_GC")
  }

  def nativeGC = Task {
    nativeGCInput().getOrElse(
      scalaNativeBridge().defaultGarbageCollector()
    )
  }

  def nativeTarget: T[Option[String]] = Task { None }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = Task {
    scalaNativeBridge().discoverCompileOptions()
  }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = Task {
    scalaNativeBridge().discoverLinkingOptions()
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
    readEnvVariable[LTO](T.env, "SCALANATIVE_LTO", LTO.values, _.value)
  }

  def nativeLTO: T[LTO] = Task { nativeLTOInput().getOrElse(LTO.None) }

  // Shall we optimize the resulting NIR code?
  protected def nativeOptimizeInput: T[Option[Boolean]] = Task.Input {
    readEnvVariable[Boolean](T.env, "SCALANATIVE_OPTIMIZE", Seq(true, false), _.toString)
  }

  def nativeOptimize: T[Boolean] = Task { nativeOptimizeInput().getOrElse(true) }

  /** Build target for current compilation */
  def nativeBuildTarget: T[BuildTarget] = Task { BuildTarget.Application }

  private def nativeConfig: Task[NativeConfig] = Task.Anon {
    val classpath = runClasspath().map(_.path).filter(_.toIO.exists).toList

    scalaNativeBridge().config(
      finalMainClassOpt(),
      classpath.map(_.toIO),
      nativeWorkdir().toIO,
      nativeClang().toIO,
      nativeClangPP().toIO,
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
      toWorkerApi(logLevel()),
      toWorkerApi(nativeBuildTarget())
    ) match {
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

  // Generates native binary
  def nativeLink = Task {
    os.Path(scalaNativeBridge().nativeLink(
      nativeConfig().config,
      T.dest.toIO
    ))
  }

  // Runs the native binary
  override def run(args: Task[Args] = Task.Anon(Args())) = Task.Command {
    Jvm.runSubprocess(
      commandArgs = Vector(nativeLink().toString) ++ args().value,
      envArgs = forkEnv(),
      workingDir = forkWorkingDir()
    )
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon {
    Some((
      ScalaBuildTarget.dataKind,
      ScalaBuildTarget(
        scalaOrganization = scalaOrganization(),
        scalaVersion = scalaVersion(),
        scalaBinaryVersion = ZincWorkerUtil.scalaBinaryVersion(scalaVersion()),
        ScalaPlatform.Native,
        jars = scalaCompilerClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq,
        jvmBuildTarget = None
      )
    ))
  }

  override def transitiveIvyDeps: T[Agg[BoundDep]] = Task {

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

    val exclusions = scalaBinaryVersionToExclude.flatMap { scalaBinVersion =>
      nativeStandardLibraries.map(library =>
        "org.scala-native" -> s"$library${nativeSuffix}_$scalaBinVersion"
      )
    }

    super.transitiveIvyDeps().map { dep =>
      dep.exclude(exclusions: _*)
    }
  }

  override def prepareOffline(all: Flag): Command[Unit] = {
    val tasks =
      if (all.value) Seq(
        scalaNativeWorkerClasspath,
        bridgeFullClassPath
      )
      else Seq()
    Task.Command {
      super.prepareOffline(all)()
      T.sequence(tasks)()
      ()
    }
  }

  override def zincAuxiliaryClassFileExtensions: T[Seq[String]] =
    super.zincAuxiliaryClassFileExtensions() :+ "nir"

}

trait TestScalaNativeModule extends ScalaNativeModule with TestModule {
  override def resources: T[Seq[PathRef]] = super[ScalaNativeModule].resources
  override def testLocal(args: String*): Command[(String, Seq[TestResult])] =
    Task.Command { test(args: _*)() }
  override protected def testTask(
      args: Task[Seq[String]],
      globSeletors: Task[Seq[String]]
  ): Task[(String, Seq[TestResult])] = Task.Anon {

    val (close, framework) = scalaNativeBridge().getFramework(
      nativeLink().toIO,
      forkEnv() ++
        Map(
          EnvVars.MILL_TEST_RESOURCE_DIR -> resources().map(_.path).mkString(";"),
          EnvVars.MILL_WORKSPACE_ROOT -> T.workspace.toString
        ),
      toWorkerApi(logLevel()),
      testFramework()
    )

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args(),
      T.testReporter,
      cls => TestRunnerUtils.globFilter(globSeletors())(cls.getName)
    )
    val res = TestModule.handleResults(doneMsg, results, T.ctx(), testReportXml())
    // Hack to try and let the Scala Native subprocess finish streaming it's stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close()
    res
  }
  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"org.scala-native::test-interface::${scalaNativeVersion()}"
  )
  override def mainClass: T[Option[String]] = Some("scala.scalanative.testinterface.TestMain")
}

trait SbtNativeModule extends ScalaNativeModule with SbtModule
