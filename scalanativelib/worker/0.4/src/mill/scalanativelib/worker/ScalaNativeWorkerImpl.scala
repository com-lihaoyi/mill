package mill.scalanativelib.worker

import java.io.File
import java.lang.System.{err, out}

import mill.scalanativelib.worker.api._
import scala.scalanative.util.Scope
import scala.scalanative.build.{
  Build,
  Config,
  Discover,
  GC,
  Logger,
  LTO,
  Mode,
  NativeConfig => ScalaNativeNativeConfig
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
      mainClass: String,
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
      logLevel: NativeLogLevel
  ): Object = {
    val entry = if (patchIsGreaterThanOrEqual(3)) mainClass else mainClass + "$"
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
    if (patchIsGreaterThanOrEqual(4)) {
      nativeConfig = nativeConfig.withEmbedResources(nativeEmbedResources)
    }
    if (patchIsGreaterThanOrEqual(9)) {
      nativeConfig = nativeConfig.withIncrementalCompilation(nativeIncrementalCompilation)
    }
    val config =
      Config.empty
        .withMainClass(entry)
        .withClassPath(classpath.map(_.toPath))
        .withWorkdir(nativeWorkdir.toPath)
        .withCompilerConfig(nativeConfig)
        .withLogger(logger(logLevel))
    config
  }

  def nativeLink(nativeConfig: Object, outPath: File): File = {
    val config = nativeConfig.asInstanceOf[Config]
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
