package mill.scalanativelib.worker

import java.io.File
import java.lang.System.{err, out}

import scala.scalanative.util.Scope
import scala.scalanative.build.{
  Build,
  BuildException,
  Config,
  Discover,
  GC,
  Logger,
  LTO => ScalaNativeLTO,
  Mode,
  NativeConfig => ScalaNativeNativeConfig
}
import scala.scalanative.nir.Versions
import mill.scalanativelib.api.{GetFrameworkResult, LTO, NativeConfig, NativeLogLevel, ReleaseMode}
import sbt.testing.Framework

import scala.scalanative.testinterface.adapter.TestAdapter

class ScalaNativeWorkerImpl extends mill.scalanativelib.api.ScalaNativeWorkerApi {
  private def patchIsGreaterThanOrEqual(number: Int) = Versions.current match {
    case s"0.4.$n" if n.toIntOption.exists(_ < number) => false
    case _ => true
  }

  def logger(level: NativeLogLevel) =
    Logger(
      traceFn = msg => if (level.value >= NativeLogLevel.Trace.value) err.println(s"[trace] $msg"),
      debugFn = msg => if (level.value >= NativeLogLevel.Debug.value) out.println(s"[debug] $msg"),
      infoFn = msg => if (level.value >= NativeLogLevel.Info.value) out.println(s"[info] $msg"),
      warnFn = msg => if (level.value >= NativeLogLevel.Warn.value) out.println(s"[warn] $msg"),
      errorFn = msg => if (level.value >= NativeLogLevel.Error.value) err.println(s"[error] $msg")
    )

  def discoverClang: java.io.File = Discover.clang().toFile
  def discoverClangPP: java.io.File = Discover.clangpp().toFile
  def discoverCompileOptions: Array[String] = Discover.compileOptions().toArray
  def discoverLinkingOptions: Array[String] = Discover.linkingOptions().toArray
  def defaultGarbageCollector: String = GC.default.name

  def config(
      mainClass: String,
      classpath: Array[java.io.File],
      nativeWorkdir: java.io.File,
      nativeClang: java.io.File,
      nativeClangPP: java.io.File,
      nativeTarget: java.util.Optional[String],
      nativeCompileOptions: Array[String],
      nativeLinkingOptions: Array[String],
      nativeGC: String,
      nativeLinkStubs: Boolean,
      nativeLTO: LTO,
      releaseMode: ReleaseMode,
      nativeOptimize: Boolean,
      nativeEmbedResources: Boolean,
      logLevel: NativeLogLevel
  ): NativeConfig = {
    val entry = if (patchIsGreaterThanOrEqual(3)) mainClass else mainClass + "$"
    var nativeConfig =
      ScalaNativeNativeConfig.empty
        .withClang(nativeClang.toPath)
        .withClangPP(nativeClangPP.toPath)
        .withTargetTriple(if (nativeTarget.isPresent) Some(nativeTarget.get) else None)
        .withCompileOptions(nativeCompileOptions)
        .withLinkingOptions(nativeLinkingOptions)
        .withGC(GC(nativeGC))
        .withLinkStubs(nativeLinkStubs)
        .withMode(Mode(releaseMode.value))
        .withOptimize(nativeOptimize)
        .withLTO(ScalaNativeLTO(nativeLTO.value))
    if (patchIsGreaterThanOrEqual(4)) {
      nativeConfig = nativeConfig.withEmbedResources(nativeEmbedResources)
    }
    val config =
      Config.empty
        .withMainClass(entry)
        .withClassPath(classpath.map(_.toPath))
        .withWorkdir(nativeWorkdir.toPath)
        .withCompilerConfig(nativeConfig)
        .withLogger(logger(logLevel))
    new NativeConfig(config)
  }

  def nativeLink(nativeConfig: NativeConfig, outPath: java.io.File): java.io.File = {
    val config = nativeConfig.config.asInstanceOf[Config]
    Build.build(config, outPath.toPath)(Scope.unsafe())
    outPath
  }

  def getFramework(
      testBinary: File,
      envVars: java.util.Map[String, String],
      logLevel: NativeLogLevel,
      frameworkName: String
  ): GetFrameworkResult = {
    import collection.JavaConverters._

    val config = TestAdapter.Config()
      .withBinaryFile(testBinary)
      .withEnvVars(envVars.asScala.toMap)
      .withLogger(logger(logLevel))

    val adapter = new TestAdapter(config)

    new GetFrameworkResult(
      new Runnable {
        def run(): Unit = adapter.close()
      },
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }
}
