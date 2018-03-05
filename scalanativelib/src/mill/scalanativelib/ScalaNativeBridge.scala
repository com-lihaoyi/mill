package mill.scalanativelib

import java.net.URLClassLoader
import ammonite.ops.Path
import mill.define.Discover
import mill.{Agg, T}


class ScalaNativeWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaNativeBridge)]

  def bridge(toolsClasspath: Agg[Path]) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = new URLClassLoader(
          toolsClasspath.map(_.toIO.toURI.toURL).toArray,
          getClass.getClassLoader
        )
        try {
          val bridge = cl
            .loadClass("mill.scalanativelib.bridge.ScalaNativeBridge")
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[ScalaNativeBridge]
          scalaInstanceCache = Some((classloaderSig, bridge))
          bridge
        }
        catch {
          case e: Exception =>
            e.printStackTrace()
            throw e
        }
    }
  }
}


// result wrappers to preserve some type saftey
case class NativeConfig(config: Any)
case class LinkResult(result: Any)
case class OptimizerResult(result: Any)

trait ScalaNativeBridge {
  def llvmClangVersions: Seq[(String,String)]
  def llvmDiscover(binaryName: String, binaryVersions: Seq[(String, String)]): Path
  def llvmCheckThatClangIsRecentEnough(pathToClangBinary: Path): Unit
  def llvmDetectTarget(clang: Path, workDir: Path): String
  def llvmDefaultCompileOptions: Seq[String]
  def llvmDefaultLinkingOptions: Seq[String]

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
             releaseMode: Boolean): NativeConfig

  def defaultGarbageCollector: String

  def nativeLinkNIR(config:NativeConfig): LinkResult
  def nativeOptimizeNIR(nativeConfig: NativeConfig, linkResult: LinkResult): OptimizerResult
  def nativeGenerateLL(nativeConfig: NativeConfig, optimized: OptimizerResult): Unit
  def compileLL(nativeConfig: NativeConfig, nativeLL: Seq[Path]): Seq[Path]
  def unpackNativeLibrary(nativeLib: Path, workDir: Path): Path
  def nativeCompileLib(nativeConfig: NativeConfig, nativeLib: Path, workDir: Path, linkResult: LinkResult): Path
  def linkLL(nativeConfig: NativeConfig, linkResult: LinkResult, compiledLL: Seq[Path], compiledLib: Path, out: Path): Path

  def nativeAvailableDependencies(runClasspath: Seq[Path]): Seq[String]
  def nativeExternalDependencies(compileClasses: Path): Seq[String]
}

object ScalaNativeBridge extends mill.define.ExternalModule {
  def scalaNativeBridge = T.worker { new ScalaNativeWorker() }
  lazy val millDiscover = Discover[this.type]
}
