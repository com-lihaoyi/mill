package mill.scalanativelib.api

import upickle.default.{macroRW, ReadWriter => RW}
import java.io.File
import sbt.testing.Framework

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


sealed abstract class NativeLogLevel(val level: Int) extends Ordered[NativeLogLevel] {
  def compare(that: NativeLogLevel) =  this.level - that.level
}

object NativeLogLevel {
  case object Error extends NativeLogLevel(200)
  case object Warn extends NativeLogLevel(300)
  case object Info extends NativeLogLevel(400)
  case object Debug extends NativeLogLevel(500)
  case object Trace extends NativeLogLevel(600)

  implicit def rw: RW[NativeLogLevel] = macroRW
}

sealed abstract class ReleaseMode(val name: String)

object ReleaseMode {
  case object Debug extends ReleaseMode("debug")                // fast compile, little optimization
  case object Release extends ReleaseMode("release")            // same as ReleaseFull for versions 0.3.x
  case object ReleaseFast extends ReleaseMode("release-fast")   // runtime optimize, faster compile, smaller binary
  case object ReleaseFull extends ReleaseMode("release-full")   // runtime optimize, prefer speed over compile time and size

  implicit def rw: RW[ReleaseMode] = macroRW
}

// result wrapper to preserve some type safety
case class NativeConfig(config: Any)