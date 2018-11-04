package mill.scalanativelib

import java.io.File
import java.net.URLClassLoader

import mill.define.{Discover, Worker}
import mill.{Agg, T}
import sbt.testing.Framework


class ScalaNativeWorker {
  private var scalaInstanceCache = Option.empty[(Long, ScalaNativeWorkerApi)]

  def impl(toolsClasspath: Agg[os.Path]): ScalaNativeWorkerApi = {
    val classloaderSig = toolsClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scalaInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = new URLClassLoader(
          toolsClasspath.map(_.toIO.toURI.toURL).toArray,
          getClass.getClassLoader
        )
        try {
          val bridge = cl
            .loadClass("mill.scalanativelib.worker.ScalaNativeWorkerImpl")
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[ScalaNativeWorkerApi]
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

trait ScalaNativeWorkerApi {
  def discoverClang: os.Path
  def discoverClangPP: os.Path
  def discoverTarget(clang: os.Path, workDir: os.Path): String
  def discoverCompileOptions: Seq[String]
  def discoverLinkingOptions: Seq[String]

  def config(nativeLibJar: os.Path,
             mainClass: String,
             classpath: Seq[os.Path],
             nativeWorkdir: os.Path,
             nativeClang: os.Path,
             nativeClangPP: os.Path,
             nativeTarget: String,
             nativeCompileOptions: Seq[String],
             nativeLinkingOptions: Seq[String],
             nativeGC: String,
             nativeLinkStubs: Boolean,
             releaseMode: ReleaseMode,
             logLevel: NativeLogLevel): NativeConfig

  def defaultGarbageCollector: String
  def nativeLink(nativeConfig: NativeConfig, outPath: os.Path): os.Path

  def newScalaNativeFrameWork(framework: Framework, id: Int, testBinary: File,
                              logLevel: NativeLogLevel, envVars: Map[String, String]): Framework
}

object ScalaNativeWorkerApi extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] = T.worker { new ScalaNativeWorker() }
  lazy val millDiscover = Discover[this.type]
}
