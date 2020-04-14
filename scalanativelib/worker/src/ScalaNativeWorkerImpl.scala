package mill.scalanativelib.worker

import java.io.File
import java.lang.System.{err, out}

import scala.scalanative.build.{Build, Config, Discover, GC, Logger, Mode}
import mill.scalanativelib.api.{NativeConfig, NativeLogLevel, ReleaseMode}
import sbt.testing.Framework

import scala.scalanative.testinterface.ScalaNativeFramework


class ScalaNativeWorkerImpl extends mill.scalanativelib.api.ScalaNativeWorkerApi {
  def logger(level: NativeLogLevel) =
    Logger(
      debugFn = msg => if (level.value >= NativeLogLevel.Debug.value) out.println(msg),
      infoFn  = msg => if (level.value >= NativeLogLevel.Info.value)  out.println(msg),
      warnFn  = msg => if (level.value >= NativeLogLevel.Warn.value)  out.println(msg),
      errorFn = msg => if (level.value >= NativeLogLevel.Error.value) err.println(msg))

  def discoverClang: java.io.File = Discover.clang().toFile
  def discoverClangPP: java.io.File = Discover.clangpp().toFile
  def discoverTarget(clang: java.io.File,
                     workdir: java.io.File): String = Discover.targetTriple(clang.toPath, workdir.toPath)
  def discoverCompileOptions: Array[String] = Discover.compileOptions().toArray
  def discoverLinkingOptions: Array[String] = Discover.linkingOptions().toArray
  def defaultGarbageCollector: String = GC.default.name

  def config(nativeLibJar: java.io.File,
             mainClass: String,
             classpath: Array[java.io.File],
             nativeWorkdir: java.io.File,
             nativeClang: java.io.File,
             nativeClangPP: java.io.File,
             nativeTarget: String,
             nativeCompileOptions: Array[String],
             nativeLinkingOptions: Array[String],
             nativeGC: String,
             nativeLinkStubs: Boolean,
             releaseMode: ReleaseMode,
             logLevel: NativeLogLevel): NativeConfig =
    {
      val entry = mainClass + "$"

      val config =
        Config.empty
          .withNativelib(nativeLibJar.toPath)
          .withMainClass(entry)
          .withClassPath(classpath.map(_.toPath))
          .withWorkdir(nativeWorkdir.toPath)
          .withClang(nativeClang.toPath)
          .withClangPP(nativeClangPP.toPath)
          .withTargetTriple(nativeTarget)
          .withCompileOptions(nativeCompileOptions)
          .withLinkingOptions(nativeLinkingOptions)
          .withGC(GC(nativeGC))
          .withLinkStubs(nativeLinkStubs)
          .withMode(Mode(releaseMode.name))
          .withLogger(logger(logLevel))
      new NativeConfig(config)
    }

  def nativeLink(nativeConfig: NativeConfig, outPath: java.io.File): java.io.File = {
    val config = nativeConfig.config.asInstanceOf[Config]
    Build.build(config, outPath.toPath)
    outPath
  }

  override def newScalaNativeFrameWork(framework: Framework, id: Int, testBinary: File,
                                       logLevel: NativeLogLevel, envVars: java.util.Map[String, String]): Framework =
  {
    import collection.JavaConverters._
    new ScalaNativeFramework(framework, id, logger(logLevel), testBinary, envVars.asScala.toMap)
  }
}
