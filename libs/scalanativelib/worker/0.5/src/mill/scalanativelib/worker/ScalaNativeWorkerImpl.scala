package mill.scalanativelib.worker

import java.io.File

import mill.scalanativelib.worker.api._
import scala.scalanative.util.Scope
import scala.scalanative.build.{
  Build,
  BuildTarget => ScalaNativeBuildTarget,
  Config,
  Discover,
  GC,
  Logger,
  LTO,
  Mode,
  NativeConfig => ScalaNativeNativeConfig
}
import scala.scalanative.testinterface.adapter.TestAdapter

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.file.Files

class ScalaNativeWorkerImpl extends mill.scalanativelib.worker.api.ScalaNativeWorkerApi {
  implicit val scope: Scope = Scope.forever

  def logger(level: NativeLogLevel): Logger = {
    // Console.err needs to be stored at instantiation time so it saves the right threadlocal
    // value and can be used by the Scala Native toolchain's threads without losing logs
    val err = Console.err
    Logger(
      traceFn =
        msg => if (level.value >= NativeLogLevel.Trace.value) err.println(s"[trace] $msg"),
      debugFn =
        msg => if (level.value >= NativeLogLevel.Debug.value) err.println(s"[debug] $msg"),
      infoFn =
        msg => if (level.value >= NativeLogLevel.Info.value) err.println(s"[info] $msg"),
      warnFn =
        msg => if (level.value >= NativeLogLevel.Warn.value) err.println(s"[warn] $msg"),
      errorFn =
        msg => if (level.value >= NativeLogLevel.Error.value) err.println(s"[error] $msg")
    )
  }

  def discoverClang(): File = Discover.clang().toFile
  def discoverClangPP(): File = Discover.clangpp().toFile
  def discoverCompileOptions(): Seq[String] = Discover.compileOptions()
  def discoverLinkingOptions(): Seq[String] = Discover.linkingOptions()
  def defaultGarbageCollector(): String = GC.default.name

  def config(
      mainClass: Either[String, String],
      classpath: Seq[File],
      nativeWorkdir: File,
      nativeClang: File,
      nativeClangPP: File,
      nativeTarget: Option[String],
      nativeCompileOptions: Seq[String],
      nativeLinkingOptions: Seq[String],
      nativeGC: String,
      nativeLinkStubs: Boolean,
      nativeLTO: String,
      releaseMode: String,
      nativeOptimize: Boolean,
      nativeEmbedResources: Boolean,
      nativeIncrementalCompilation: Boolean,
      nativeDump: Boolean,
      nativeMultithreading: Option[Boolean],
      nativeServiceProviders: Map[String, Seq[String]],
      logLevel: NativeLogLevel,
      buildTarget: BuildTarget,
      sourceLevelDebuggingConfig: SourceLevelDebuggingConfig,
      baseName: String
  ): Either[String, Config] = {
    val nativeConfig =
      ScalaNativeNativeConfig.empty
        .withClang(nativeClang.toPath)
        .withClangPP(nativeClangPP.toPath)
        .withTargetTriple(nativeTarget)
        .withCompileOptions(nativeCompileOptions)
        .withLinkingOptions(nativeLinkingOptions)
        .withGC(GC(nativeGC))
        .withLinkStubs(nativeLinkStubs)
        .withMode(Mode(releaseMode))
        .withOptimize(nativeOptimize)
        .withLTO(LTO(nativeLTO))
        .withDump(nativeDump)
        .withBuildTarget(buildTarget match {
          case BuildTarget.Application => ScalaNativeBuildTarget.application
          case BuildTarget.LibraryDynamic => ScalaNativeBuildTarget.libraryDynamic
          case BuildTarget.LibraryStatic => ScalaNativeBuildTarget.libraryStatic
        })
        .withEmbedResources(nativeEmbedResources)
        .withIncrementalCompilation(nativeIncrementalCompilation)
        .withMultithreading(nativeMultithreading)
        .withServiceProviders(nativeServiceProviders)
        .withBaseName(baseName)
        .withSourceLevelDebuggingConfig(
          _.enabled(sourceLevelDebuggingConfig.enabled)
            .generateFunctionSourcePositions(
              sourceLevelDebuggingConfig.generateFunctionSourcePositions
            )
            .generateLocalVariables(sourceLevelDebuggingConfig.generateLocalVariables)
            .withCustomSourceRoots(sourceLevelDebuggingConfig.customSourceRoots)
        )

    val config = Config.empty
      .withClassPath(classpath.map(_.toPath))
      .withBaseDir(nativeWorkdir.toPath)
      .withCompilerConfig(nativeConfig)
      .withLogger(logger(logLevel))

    if (buildTarget == BuildTarget.Application) {
      mainClass match {
        case Left(error) =>
          Left(error)
        case Right(mainClass) =>
          Right(config.withMainClass(Some(mainClass)))
      }
    } else Right(config)
  }

  def nativeLink(nativeConfig: Object, outDirectory: File): File = {
    val config = nativeConfig.asInstanceOf[Config]

    val result = Await.result(Build.buildCached(config), Duration.Inf)

    val resultInOutDirectory =
      Files.move(result, outDirectory.toPath().resolve(result.getFileName()))

    resultInOutDirectory.toFile()
  }

  def getFramework(
      testBinary: File,
      envVars: Map[String, String],
      logLevel: NativeLogLevel,
      frameworkName: String
  ): (() => Unit, sbt.testing.Framework) = {
    val config = TestAdapter.Config()
      .withBinaryFile(testBinary)
      .withEnvVars(envVars)
      .withLogger(logger(logLevel))

    val adapter = new TestAdapter(config)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }
}
