package mill.scalanativelib.worker.api

import java.io.File

private[scalanativelib] trait ScalaNativeWorkerApi {

  def discoverClang(): File
  def discoverClangPP(): File
  def discoverCompileOptions(): Seq[String]
  def discoverLinkingOptions(): Seq[String]
  def defaultGarbageCollector(): String

  def config(
      mainClass: Either[String, String],
      classpath: Seq[File],
      nativeWorkdir: File,
      nativeClang: File,
      nativeClangPP: File,
      nativeTarget: Option[String],
      nativeCompileOptions: Seq[String],
      nativeLinkingOptions: Seq[String],
      nativeGC: String,
      nativeLinkStubs: Boolean,
      nativeLTO: String,
      releaseMode: String,
      nativeOptimize: Boolean,
      nativeEmbedResources: Boolean,
      nativeIncrementalCompilation: Boolean,
      nativeDump: Boolean,
      nativeMultithreading: Option[Boolean],
      nativeServiceProviders: Map[String, Seq[String]],
      logLevel: NativeLogLevel,
      buildTarget: BuildTarget
  ): Either[String, Object]

  def nativeLink(nativeConfig: Object, outPath: File): File

  def getFramework(
      testBinary: File,
      envVars: Map[String, String],
      logLevel: NativeLogLevel,
      frameworkName: String
  ): (() => Unit, sbt.testing.Framework)
}

private[scalanativelib] sealed abstract class NativeLogLevel(val value: Int)
private[scalanativelib] object NativeLogLevel {
  case object Error extends NativeLogLevel(200)
  case object Warn extends NativeLogLevel(300)
  case object Info extends NativeLogLevel(400)
  case object Debug extends NativeLogLevel(500)
  case object Trace extends NativeLogLevel(600)
}

private[scalanativelib] sealed trait BuildTarget
private[scalanativelib] object BuildTarget {
  case object Application extends BuildTarget
  case object LibraryDynamic extends BuildTarget
  case object LibraryStatic extends BuildTarget
}
