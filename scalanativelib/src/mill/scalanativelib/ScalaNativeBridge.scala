package mill.scalanativelib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.Path
import mill.define.{Discover, Worker}
import mill.{Agg, T}
import sbt.testing.Framework


class ScalaNativeWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaNativeBridge)]

  def bridge(toolsClasspath: Agg[Path]): ScalaNativeBridge = {
    val classloaderSig = toolsClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
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


// result wrapper to preserve some type safety
case class NativeConfig(config: Any)

trait ScalaNativeBridge {
  def discoverClang: Path
  def discoverClangPP: Path
  def discoverTarget(clang: Path, workDir: Path): String
  def discoverCompileOptions: Seq[String]
  def discoverLinkingOptions: Seq[String]

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
             logLevel: NativeLogLevel): NativeConfig

  def defaultGarbageCollector: String
  def nativeLink(nativeConfig: NativeConfig, outPath: Path): Path

  def newScalaNativeFrameWork(framework: Framework, id: Int, testBinary: File,
                              logLevel: NativeLogLevel, envVars: Map[String, String]): Framework
}

object ScalaNativeBridge extends mill.define.ExternalModule {
  def scalaNativeBridge: Worker[ScalaNativeWorker] = T.worker { new ScalaNativeWorker() }
  lazy val millDiscover = Discover[this.type]
}
