package mill
package scalanativelib

import mainargs.Flag
import mill.api.Loose.Agg
import mill.api.{Result, internal}
import mill.define.{Command, Target, Task}
import mill.modules.Jvm
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
import mill.testrunner.TestRunner
import mill.scalanativelib.api._
import mill.scalanativelib.worker.{ScalaNativeWorkerExternalModule, api => workerApi}

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import upickle.default.{macroRW, ReadWriter => RW}

trait ScalaNativeModule extends ScalaModule { outer =>
  def scalaNativeVersion: T[String]
  override def platformSuffix = s"_native${scalaNativeBinaryVersion()}"

  trait Tests extends ScalaNativeModuleTests

  trait ScalaNativeModuleTests extends ScalaModuleTests with TestScalaNativeModule {
    override def scalaNativeVersion = outer.scalaNativeVersion()
    override def releaseMode = T { outer.releaseMode() }
    override def logLevel = outer.logLevel()
  }

  def scalaNativeBinaryVersion =
    T { ZincWorkerUtil.scalaNativeBinaryVersion(scalaNativeVersion()) }

  def scalaNativeWorkerVersion =
    T { ZincWorkerUtil.scalaNativeWorkerVersion(scalaNativeVersion()) }

  def scalaNativeWorkerClasspath = T {
    val workerKey =
      s"MILL_SCALANATIVE_WORKER_${scalaNativeWorkerVersion()}"
        .replace('.', '_')
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-scalanativelib-worker-${scalaNativeWorkerVersion()}",
      repositoriesTask(),
      resolveFilter = _.toString.contains("mill-scalanativelib-worker")
    )
  }

  def toolsIvyDeps = T {
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

  def nativeIvyDeps: T[Agg[Dep]] = T {
    val scalaVersionSpecific =
      if (ZincWorkerUtil.isScala3(scalaVersion()))
        Agg(ivy"org.scala-native::scala3lib::${scalaNativeVersion()}")
      else
        Agg(ivy"org.scala-native::scalalib::${scalaNativeVersion()}")

    Agg(
      ivy"org.scala-native::nativelib::${scalaNativeVersion()}",
      ivy"org.scala-native::javalib::${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib::${scalaNativeVersion()}"
    ) ++ scalaVersionSpecific
  }

  override def scalaLibraryIvyDeps = T {
    super.scalaLibraryIvyDeps().map(dep =>
      dep.copy(cross = dep.cross match {
        case c: CrossVersion.Constant => c.copy(platformed = false)
        case c: CrossVersion.Binary => c.copy(platformed = false)
        case c: CrossVersion.Full => c.copy(platformed = false)
      })
    )
  }

  /** Adds [[nativeIvyDeps]] as mandatory dependencies. */
  override def mandatoryIvyDeps = T {
    super.mandatoryIvyDeps() ++ nativeIvyDeps()
  }

  def bridgeFullClassPath: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      repositoriesTask(),
      toolsIvyDeps().map(Lib.depToBoundDep(_, mill.BuildInfo.scalaVersion, "")),
      ctx = Some(T.log)
    ).map(t => (scalaNativeWorkerClasspath() ++ t))
  }

  private[scalanativelib] def scalaNativeBridge = T.task {
    ScalaNativeWorkerExternalModule.scalaNativeWorker().bridge(bridgeFullClassPath())
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = T {
    super.scalacPluginIvyDeps() ++ Agg(
      ivy"org.scala-native:::nscplugin:${scalaNativeVersion()}"
    )
  }

  def logLevel: Target[NativeLogLevel] = T { NativeLogLevel.Info }

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

  protected def releaseModeInput: Target[Option[ReleaseMode]] = T.input {
    readEnvVariable[ReleaseMode](T.env, "SCALANATIVE_MODE", ReleaseMode.values, _.value)
  }

  def releaseMode: Target[ReleaseMode] = T {
    releaseModeInput().getOrElse(ReleaseMode.Debug)
  }

  def nativeWorkdir = T { T.dest }

  // Location of the clang compiler
  def nativeClang = T {
    os.Path(
      scalaNativeBridge().discoverClang()
    )
  }

  // Location of the clang++ compiler
  def nativeClangPP = T {
    os.Path(
      scalaNativeBridge().discoverClangPP()
    )
  }

  // GC choice, either "none", "boehm", "immix" or "commix"
  protected def nativeGCInput: Target[Option[String]] = T.input {
    T.env.get("SCALANATIVE_GC")
  }

  def nativeGC = T {
    nativeGCInput().getOrElse(
      scalaNativeBridge().defaultGarbageCollector()
    )
  }

  def nativeTarget: Target[Option[String]] = T { None }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = T {
    scalaNativeBridge().discoverCompileOptions()
  }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = T {
    scalaNativeBridge().discoverLinkingOptions()
  }

  // Whether to link `@stub` methods, or ignore them
  def nativeLinkStubs = T { false }

  /**
   * Shall the resource files be embedded in the resulting binary file? Allows
   *  the use of getClass().getResourceAsStream() on the included files. Will
   *  not embed files with certain extensions, including ".c", ".h", ".scala"
   *  and ".class".
   */
  def nativeEmbedResources = T { false }

  /** Shall we use the incremental compilation? */
  def nativeIncrementalCompilation = T { false }

  /** Shall linker dump intermediate NIR after every phase? */
  def nativeDump = T { false }

  // The LTO mode to use used during a release build
  protected def nativeLTOInput: Target[Option[LTO]] = T.input {
    readEnvVariable[LTO](T.env, "SCALANATIVE_LTO", LTO.values, _.value)
  }

  def nativeLTO: Target[LTO] = T { nativeLTOInput().getOrElse(LTO.None) }

  // Shall we optimize the resulting NIR code?
  protected def nativeOptimizeInput: Target[Option[Boolean]] = T.input {
    readEnvVariable[Boolean](T.env, "SCALANATIVE_OPTIMIZE", Seq(true, false), _.toString)
  }

  def nativeOptimize: Target[Boolean] = T { nativeOptimizeInput().getOrElse(true) }

  private def nativeConfig: Task[NativeConfig] = T.task {
    val classpath = runClasspath().map(_.path).filter(_.toIO.exists).toList

    NativeConfig(
      scalaNativeBridge().config(
        finalMainClass(),
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
        toWorkerApi(logLevel())
      )
    )
  }

  private[scalanativelib] def toWorkerApi(logLevel: api.NativeLogLevel): workerApi.NativeLogLevel =
    logLevel match {
      case api.NativeLogLevel.Error => workerApi.NativeLogLevel.Error
      case api.NativeLogLevel.Warn => workerApi.NativeLogLevel.Warn
      case api.NativeLogLevel.Info => workerApi.NativeLogLevel.Info
      case api.NativeLogLevel.Debug => workerApi.NativeLogLevel.Debug
      case api.NativeLogLevel.Trace => workerApi.NativeLogLevel.Trace
    }

  // Generates native binary
  def nativeLink = T {
    os.Path(scalaNativeBridge().nativeLink(
      nativeConfig().config,
      (T.dest / "out").toIO
    ))
  }

  // Runs the native binary
  override def run(args: String*) = T.command {
    Jvm.runSubprocess(
      commandArgs = Vector(nativeLink().toString) ++ args,
      envArgs = forkEnv(),
      workingDir = forkWorkingDir()
    )
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task {
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

  override def transitiveIvyDeps: T[Agg[BoundDep]] = T {

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
    T.command {
      super.prepareOffline(all)()
      T.sequence(tasks)()
      ()
    }
  }

}

trait TestScalaNativeModule extends ScalaNativeModule with TestModule {
  override def testLocal(args: String*) = T.command { test(args: _*) }
  override protected def testTask(
      args: Task[Seq[String]],
      globSeletors: Task[Seq[String]]
  ): Task[(String, Seq[TestRunner.Result])] = T.task {

    val (close, framework) = scalaNativeBridge().getFramework(
      nativeLink().toIO,
      forkEnv(),
      toWorkerApi(logLevel()),
      testFramework()
    )

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args(),
      T.testReporter,
      TestRunner.globFilter(globSeletors())
    )
    val res = TestModule.handleResults(doneMsg, results, Some(T.ctx()))
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
