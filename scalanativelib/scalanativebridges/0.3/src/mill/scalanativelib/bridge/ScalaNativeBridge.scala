package mill.scalanativelib.bridge

import java.lang.System.{err, out}

import scala.scalanative.build.{Build, Config, Discover, GC, Logger, Mode}
import ammonite.ops.Path
import mill.scalanativelib.{NativeConfig, ReleaseMode}

class ScalaNativeBridge extends mill.scalanativelib.ScalaNativeBridge {
  val logger =
    Logger(debugFn = msg => Unit, //err.println(msg),
      infoFn  = msg => out.println(msg),
      warnFn  = msg => out.println(msg),
      errorFn = msg => err.println(msg))

  def discoverClang: Path = {
    Path(Discover.clang())
  }

  def discoverClangPP: Path = {
    Path(Discover.clangpp())
  }

  def discoverTarget(clang: Path, workdir: Path): String = Discover.targetTriple(clang.toNIO, workdir.toNIO)
  def discoverCompileOptions: Seq[String] = Discover.compileOptions()
  def discoverLinkingOptions: Seq[String] = Discover.linkingOptions()

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
             releaseMode: ReleaseMode): NativeConfig =
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
      NativeConfig(config)
    }

  def nativeLink(nativeConfig: NativeConfig, outPath: Path): Path = {
    val config = nativeConfig.config.asInstanceOf[Config]
    Build.build(config, outPath.toNIO)
    outPath
  }
}
