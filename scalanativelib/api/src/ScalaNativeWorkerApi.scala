//package mill.scalanativelib.api
//
//import upickle.default.{macroRW, ReadWriter => RW}
//import java.io.File
//import sbt.testing.Framework
//
//trait ScalaNativeWorkerApi {
//  def discoverClang: os.Path
//  def discoverClangPP: os.Path
//  def discoverTarget(clang: os.Path, workDir: os.Path): String
//  def discoverCompileOptions: Seq[String]
//  def discoverLinkingOptions: Seq[String]
//
//  def config(nativeLibJar: os.Path,
//             mainClass: String,
//             classpath: Seq[os.Path],
//             nativeWorkdir: os.Path,
//             nativeClang: os.Path,
//             nativeClangPP: os.Path,
//             nativeTarget: String,
//             nativeCompileOptions: Seq[String],
//             nativeLinkingOptions: Seq[String],
//             nativeGC: String,
//             nativeLinkStubs: Boolean,
//             releaseMode: ReleaseMode,
//             logLevel: NativeLogLevel): NativeConfig
//
//  def defaultGarbageCollector: String
//  def nativeLink(nativeConfig: NativeConfig, outPath: os.Path): os.Path
//
//  def newScalaNativeFrameWork(framework: Framework, id: Int, testBinary: File,
//                              logLevel: NativeLogLevel, envVars: Map[String, String]): Framework
//}
//
