package mill.scalanativelib.worker

import java.io.File
import java.lang.System.{err, out}

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
  NativeConfig => ScalaNativeNativeConfig,
  MillUtils
}
import scala.scalanative.nir.Versions
import scala.scalanative.testinterface.adapter.TestAdapter

import scala.util.{Success, Try}

class ScalaNativeWorkerImpl extends mill.scalanativelib.worker.api.ScalaNativeWorkerApi {
  private def patchIsGreaterThanOrEqual(number: Int) = {
    val patch = Versions.current.stripPrefix("0.4.")
    Try(patch.toInt) match {
      case Success(n) if n < number => false
      case _ => true
    }
  }

  def logger(level: NativeLogLevel) =
    Logger(
      traceFn = msg => if (level.value >= NativeLogLevel.Trace.value) err.println(s"[trace] $msg"),
      debugFn = msg => if (level.value >= NativeLogLevel.Debug.value) out.println(s"[debug] $msg"),
      infoFn = msg => if (level.value >= NativeLogLevel.Info.value) out.println(s"[info] $msg"),
      warnFn = msg => if (level.value >= NativeLogLevel.Warn.value) out.println(s"[warn] $msg"),
      errorFn = msg => if (level.value >= NativeLogLevel.Error.value) err.println(s"[error] $msg")
    )

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
      logLevel: NativeLogLevel,
      buildTarget: BuildTarget
  ): Either[String, Config] = {
    var nativeConfig =
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
    if (patchIsGreaterThanOrEqual(8)) {
      val nativeBuildTarget = buildTarget match {
        case BuildTarget.Application => ScalaNativeBuildTarget.application
        case BuildTarget.LibraryDynamic => ScalaNativeBuildTarget.libraryDynamic
        case BuildTarget.LibraryStatic => ScalaNativeBuildTarget.libraryStatic
      }
      nativeConfig = nativeConfig.withBuildTarget(nativeBuildTarget)
    } else {
      if (buildTarget != BuildTarget.Application) {
        return Left("nativeBuildTarget not supported. Please update to Scala Native 0.4.8+")
      }
    }
    if (patchIsGreaterThanOrEqual(4)) {
      nativeConfig = nativeConfig.withEmbedResources(nativeEmbedResources)
    }
    if (patchIsGreaterThanOrEqual(9)) {
      nativeConfig = nativeConfig.withIncrementalCompilation(nativeIncrementalCompilation)
    }
    var config = Config.empty
      .withClassPath(classpath.map(_.toPath))
      .withWorkdir(nativeWorkdir.toPath)
      .withCompilerConfig(nativeConfig)
      .withLogger(logger(logLevel))

    if (buildTarget == BuildTarget.Application) {
      mainClass match {
        case Left(error) => return Left(error)
        case Right(mainClass) =>
          val entry = if (patchIsGreaterThanOrEqual(3)) mainClass else mainClass + "$"
          config = config.withMainClass(entry)
      }
    }

    Right(config)
  }

  def nativeLink(nativeConfig: Object, outDirectory: File): File = {
    val config = nativeConfig.asInstanceOf[Config]
    val compilerConfig = config.compilerConfig

    val name = if (patchIsGreaterThanOrEqual(8)) {
      val isWindows = MillUtils.targetsWindows(config)
      val isMac = MillUtils.targetsMac(config)

      val ext = if (compilerConfig.buildTarget == ScalaNativeBuildTarget.application) {
        if (MillUtils.targetsWindows(config)) ".exe" else ""
      } else if (compilerConfig.buildTarget == ScalaNativeBuildTarget.libraryDynamic) {
        if (isWindows) ".dll"
        else if (isMac) ".dylib"
        else ".so"
      } else if (compilerConfig.buildTarget == ScalaNativeBuildTarget.libraryStatic) {
        if (isWindows) ".lib"
        else ".a"
      } else {
        throw new RuntimeException(s"Unknown buildTarget ${compilerConfig.buildTarget}")
      }

      val namePrefix = if (compilerConfig.buildTarget == ScalaNativeBuildTarget.application) ""
      else {
        if (isWindows) "" else "lib"
      }
      s"${namePrefix}out${ext}"
    } else "out"

    val outPath = new File(outDirectory, name)
    Build.build(config, outPath.toPath)(Scope.unsafe())
    outPath
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
