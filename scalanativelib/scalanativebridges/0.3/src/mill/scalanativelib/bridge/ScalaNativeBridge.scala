package mill.scalanativelib.bridge

import java.lang.System.{err, out}

import scala.scalanative.build.{Config, GC, Logger, Mode, Discover, Build}
import ammonite.ops.Path
import mill.scalanativelib.NativeConfig

class ScalaNativeBridge extends mill.scalanativelib.ScalaNativeBridge {
  val logger =
    Logger(debugFn = msg => Unit, //err.println(msg),
      infoFn  = msg => out.println(msg),
      warnFn  = msg => out.println(msg),
      errorFn = msg => err.println(msg))

  def discoverClang: Path = {
    val clang = Discover.clang()
    Discover.checkThatClangIsRecentEnough(clang)
    Path(clang)
  }

  def discoverClangPP: Path = {
    val clang = Discover.clangpp()
    Discover.checkThatClangIsRecentEnough(clang)
    Path(clang)
  }

  def discoverTarget(clang: Path, workdir: Path): String = Discover.target(clang.toNIO, workdir.toNIO, logger)
  def discoverCompilationOptions: Seq[String] = Discover.compilationOptions()
  def discoverLinkingOptions: Seq[String] = Discover.linkingOptions()

  private def nativeMode(release: Boolean = false): Mode = {
    if (release) Mode.release
    else Mode.debug
  }

  def defaultGarbageCollector = GC.default.name

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
             releaseMode: Boolean): NativeConfig =
    {
      val entry = mainClass + "$"

      val config =
        Config.empty
          .withNativelib(nativeLibJar.toNIO)
          .withEntry(entry)
          .withClasspath(classpath.map(_.toNIO))
          .withWorkdir(nativeWorkdir.toNIO)
          .withClang(nativeClang.toNIO)
          .withClangPP(nativeClangPP.toNIO)
          .withTarget(nativeTarget)
          .withCompileOptions(nativeCompileOptions)
          .withLinkingOptions(nativeLinkingOptions)
          .withGC(GC(nativeGC))
          .withLinkStubs(nativeLinkStubs)
      NativeConfig(config)
    }

  def nativeLink(nativeConfig: NativeConfig, outPath: Path): Path = {
    val config = nativeConfig.config.asInstanceOf[Config]
    //val config  = nativeConfig.value.withLogger(logger)
    Build.build(config, outPath.toNIO)
    outPath
  }
}
