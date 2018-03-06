package mill.scalanativelib.bridge

import java.lang.System.{err, out}

import scala.scalanative.{build => nativebuild}
import scala.scalanative.build.{Logger, Mode}
import scala.scalanative.{linker, nir}
import scala.scalanative.io.VirtualDirectory
import scalanative.util.{Scope => ResourceScope}
import ammonite.ops.Path
import mill.scalanativelib.{LinkResult, NativeConfig, OptimizerResult}


class ScalaNativeBridge extends mill.scalanativelib.ScalaNativeBridge {
  val logger =
    Logger(debugFn = msg => Unit, //err.println(msg),
           infoFn  = msg => out.println(msg),
           warnFn  = msg => out.println(msg),
           errorFn = msg => err.println(msg))

  def llvmClangVersions: Seq[(String,String)] = nativebuild.llvm.clangVersions
  def llvmDiscover(binaryName: String, binaryVersions: Seq[(String, String)]): Path = Path(nativebuild.llvm.discover(binaryName, binaryVersions))
  def llvmCheckThatClangIsRecentEnough(pathToClangBinary: Path): Unit = nativebuild.llvm.checkThatClangIsRecentEnough(pathToClangBinary.toNIO)
  def llvmDetectTarget(clang: Path, workdir: Path): String = nativebuild.llvm.detectTarget(clang.toNIO, workdir.toNIO, logger)
  def llvmDefaultCompileOptions: Seq[String] = nativebuild.llvm.defaultCompileOptions
  def llvmDefaultLinkingOptions: Seq[String] = nativebuild.llvm.defaultLinkingOptions

  private def nativeMode(release: Boolean = false): Mode = {
    if (release) Mode.Release
    else Mode.Debug
  }

  def defaultGarbageCollector: String = nativebuild.GarbageCollector.default.name

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
      nativebuild.Config.empty
      .withNativeLib(nativeLibJar.toNIO)
      .withDriver(nativebuild.tools.OptimizerDriver(nativeMode(releaseMode)))
      .withLinkerReporter(nativebuild.tools.LinkerReporter.empty)
      .withOptimizerReporter(nativebuild.tools.OptimizerReporter.empty)
      .withEntry(entry)
      .withPaths(classpath.map(_.toNIO))
      .withWorkdir(nativeWorkdir.toNIO)
      .withClang(nativeClang.toNIO)
      .withClangPP(nativeClangPP.toNIO)
      .withTarget(nativeTarget)
      .withCompileOptions(nativeCompileOptions)
      .withLinkingOptions(nativeLinkingOptions)
      .withGC(nativebuild.GarbageCollector(nativeGC))
      .withLinkStubs(nativeLinkStubs)
    NativeConfig(config)
  }

  def nativeLinkNIR(config: NativeConfig): LinkResult = {
    val result = nativebuild.tools.link(config.config.asInstanceOf[nativebuild.Config])

    if (result.unresolved.nonEmpty) {
      result.unresolved.map(_.show).sorted.foreach { signature =>
        logger.error(s"cannot link: $signature")
      }
      throw new Exception("unable to link")
    }
    val classCount = result.defns.count {
      case _: nir.Defn.Class | _: nir.Defn.Module | _: nir.Defn.Trait => true
      case _                                                          => false
    }
    val methodCount = result.defns.count(_.isInstanceOf[nir.Defn.Define])
    logger.info(s"Discovered ${classCount} classes and ${methodCount} methods")
    LinkResult(result)
  }

  def nativeOptimizeNIR(nativeConfig: NativeConfig, linkResult: LinkResult): OptimizerResult = {
    logger.time(s"Optimizing (${nativeMode()} mode)") {
      val config = nativeConfig.config.asInstanceOf[nativebuild.Config]
      val link = linkResult.result.asInstanceOf[linker.Result]
      val result = nativebuild.tools.optimize(config, link.defns, link.dyns)
      OptimizerResult(result)
    }
  }

  def nativeGenerateLL(nativeConfig: NativeConfig, optimized: OptimizerResult): Unit = {
    val config = nativeConfig.config.asInstanceOf[nativebuild.Config]
    val optResult = optimized.result.asInstanceOf[Seq[nir.Defn]]

    logger.time("Generating intermediate code") {
      nativebuild.tools.codegen(config, optResult)
    }
  }

  def compileLL(nativeConfig: NativeConfig, nativeLL: Seq[Path]): Seq[Path] = {
    val config = nativeConfig.config.asInstanceOf[nativebuild.Config]
    nativebuild.llvm.compileLL(config, nativeLL.map(_.toNIO), logger)
      .map(Path(_))
  }

  def unpackNativeLibrary(nativeLib: Path, workDir: Path): Path = {
    Path(nativebuild.unpackNativeLibrary(nativeLib.toNIO, workDir.toNIO))
  }

  def nativeCompileLib(nativeConfig: NativeConfig, nativeLib: Path, workDir: Path, linkResult: LinkResult): Path = {
    val link = linkResult.result.asInstanceOf[linker.Result]

    val config = {
      val config0 = nativeConfig.config.asInstanceOf[nativebuild.Config]
      config0.withCompileOptions("-O2" +: config0.compileOptions)
    }

    Path(nativebuild.compileNativeLib(config, link, nativeLib.toNIO, logger))
  }

  // Link to create executable
  def linkLL(nativeConfig: NativeConfig, linkResult: LinkResult, compiledLL: Seq[Path], compiledLib: Path, out: Path): Path = {
    val config = nativeConfig.config.asInstanceOf[nativebuild.Config]
    val link = linkResult.result.asInstanceOf[linker.Result]
    nativebuild.llvm.linkLL(config, link, compiledLL.map(_.toNIO), compiledLib.toNIO, out.toNIO, logger)
    out
  }

  // List all symbols available at link time
  def nativeAvailableDependencies(runClasspath: Seq[Path]): Seq[String] = {
    ResourceScope { implicit scope =>
      val globals = runClasspath
        .collect { case p if p.toIO.exists => p.toNIO }
        .flatMap(p =>
          nativebuild.tools.LinkerPath(VirtualDirectory.real(p)).globals.toSeq)

      globals.map(_.show).sorted.toVector
    }
  }

  // List all external dependencies at link time
  def nativeExternalDependencies(compileClasses: Path): Seq[String] = {
    val classDir     = compileClasses.toNIO

    ResourceScope { implicit scope =>
      val globals = linker.ClassPath(VirtualDirectory.real(classDir)).globals
      val config  = nativebuild.Config.empty.withPaths(Seq(classDir))
      val result  = (linker.Linker(config)).link(globals.toSeq)
      result.unresolved.map(_.show).sorted
    }
  }
}
