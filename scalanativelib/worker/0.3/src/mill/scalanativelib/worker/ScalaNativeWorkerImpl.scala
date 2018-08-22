package mill.scalanativelib.worker

import java.io.File
import java.lang.System.{err, out}

import scala.scalanative.build.{Build, Config, Discover, GC, Logger, Mode}
import ammonite.ops.Path
import mill.scalanativelib.{NativeConfig, NativeLogLevel, ReleaseMode}
import sbt.testing.Framework

import scala.scalanative.testinterface.ScalaNativeFramework


class ScalaNativeWorkerImpl extends mill.scalanativelib.ScalaNativeWorkerApi {
  def logger(level: NativeLogLevel) =
    Logger(
      debugFn = msg => if (level >= NativeLogLevel.Debug) out.println(msg),
      infoFn  = msg => if (level >= NativeLogLevel.Info)  out.println(msg),
      warnFn  = msg => if (level >= NativeLogLevel.Warn)  out.println(msg),
      errorFn = msg => if (level >= NativeLogLevel.Error) err.println(msg))

  def discoverClang: Path = Path(Discover.clang())
  def discoverClangPP: Path = Path(Discover.clangpp())
  def discoverTarget(clang: Path, workdir: Path): String = Discover.targetTriple(clang.toNIO, workdir.toNIO)
  def discoverCompileOptions: Seq[String] = Discover.compileOptions()
  def discoverLinkingOptions: Seq[String] = Discover.linkingOptions()
  def defaultGarbageCollector: String = GC.default.name

  def config(nativeLibJar: Path,
             mainClass: String,
             classpath: Seq[Path],
             nativeWorkdir: Path,
             nativeClang: Path,
             nativeClangPP: Path,
             nativeTarget: String,
             nativeCompileOptions: Seq[String],
             nativeLinkingOptions: Seq[String],
             nativeGC: String,
             nativeLinkStubs: Boolean,
             releaseMode: ReleaseMode,
             logLevel: NativeLogLevel): NativeConfig =
    {
      val entry = mainClass + "$"

      val config =
        Config.empty
          .withNativelib(nativeLibJar.toNIO)
          .withMainClass(entry)
          .withClassPath(classpath.map(_.toNIO))
          .withWorkdir(nativeWorkdir.toNIO)
          .withClang(nativeClang.toNIO)
          .withClangPP(nativeClangPP.toNIO)
          .withTargetTriple(nativeTarget)
          .withCompileOptions(nativeCompileOptions)
          .withLinkingOptions(nativeLinkingOptions)
          .withGC(GC(nativeGC))
          .withLinkStubs(nativeLinkStubs)
          .withMode(Mode(releaseMode.name))
          .withLogger(logger(logLevel))
      NativeConfig(config)
    }

  def nativeLink(nativeConfig: NativeConfig, outPath: Path): Path = {
    val config = nativeConfig.config.asInstanceOf[Config]
    Build.build(config, outPath.toNIO)
    outPath
  }

  override def newScalaNativeFrameWork(framework: Framework, id: Int, testBinary: File,
                                       logLevel: NativeLogLevel, envVars: Map[String, String]): Framework =
  {
    new ScalaNativeFramework(framework, id, logger(logLevel), testBinary, envVars)
  }
}
