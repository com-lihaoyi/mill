package mill.scalanativelib.bridge

import java.lang.System.{err, out}


import scala.scalanative.{build => nativebuild}
import scala.scalanative.linker
import scala.scalanative.build.{Logger, Mode, LLVM}
import scalanative.io.VirtualDirectory
import scalanative.util.{Scope => ResourceScope}
import ammonite.ops.Path
import mill.scalanativelib.{LinkResult, NativeConfig, OptimizerResult}

class ScalaNativeBridge extends mill.scalanativelib.ScalaNativeBridge {
  val logger =
    Logger(debugFn = msg => Unit, //err.println(msg),
      infoFn  = msg => out.println(msg),
      warnFn  = msg => out.println(msg),
      errorFn = msg => err.println(msg))

  def llvmClangVersions: Seq[(String,String)] = LLVM.clangVersions
  def llvmDiscover(binaryName: String, binaryVersions: Seq[(String, String)]): Path = Path(LLVM.discover(binaryName, binaryVersions))
  def llvmCheckThatClangIsRecentEnough(pathToClangBinary: Path): Unit = LLVM.checkThatClangIsRecentEnough(pathToClangBinary.toNIO)
  def llvmDetectTarget(clang: Path, workdir: Path): String = LLVM.detectTarget(clang.toNIO, workdir.toNIO, logger)
  def llvmDefaultCompileOptions: Seq[String] = LLVM.defaultCompileOptions
  def llvmDefaultLinkingOptions: Seq[String] = LLVM.defaultLinkingOptions

  private def nativeMode(release: Boolean = false): Mode = {
    if (release) Mode.Release
    else Mode.Debug
  }

  def defaultGarbageCollector = nativebuild.GC.default.name

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
          .withNativelib(nativeLibJar.toNIO)
          .withDriver(nativebuild.OptimizerDriver(nativeMode(releaseMode)))
          .withLinkerReporter(nativebuild.LinkerReporter.empty)
          .withOptimizerReporter(nativebuild.OptimizerReporter.empty)
          .withEntry(entry)
          .withPaths(classpath.map(_.toNIO))
          .withWorkdir(nativeWorkdir.toNIO)
          .withClang(nativeClang.toNIO)
          .withClangPP(nativeClangPP.toNIO)
          .withTarget(nativeTarget)
          .withCompileOptions(nativeCompileOptions)
          .withLinkingOptions(nativeLinkingOptions)
          .withGC(nativebuild.GC(nativeGC))
          .withLinkStubs(nativeLinkStubs)
      NativeConfig(config)
    }

  def nativeLink(nativeConfig: NativeConfig, outPath: Path): Path = {
    val config = nativeConfig.config.asInstanceOf[nativebuild.Config]
    //val config  = nativeConfig.value.withLogger(logger)
    nativebuild.build(config, outPath.toNIO)
    outPath
  }

  //  // List all symbols available at link time
  //  def nativeAvailableDependencies(runClasspath: Seq[Path]): Seq[String] = {
  //    ResourceScope { implicit scope =>
  //      val globals = runClasspath
  //        .collect { case p if p.toIO.exists => p.toNIO }
  //        .flatMap(p =>
  //          nativebuild.LinkerPath(VirtualDirectory.real(p)).globals.toSeq)
  //
  //      globals.map(_.show).sorted.toVector
  //    }
  //  }
  //
  //  // List all external dependencies at link time
  //  def nativeExternalDependencies(compileClasses: Path): Seq[String] = {
  //    val classDir     = compileClasses.toNIO
  //
  //    ResourceScope { implicit scope =>
  //      val globals = linker.ClassPath(VirtualDirectory.real(classDir)).globals
  //      val config  = nativebuild.Config.empty.withPaths(Seq(classDir))
  //      val result  = (linker.Linker(config)).link(globals.toSeq)
  //
  //      result.unresolved.map(_.show).sorted
  //    }
  //  }
}
