package mill
package scalanativelib

import ammonite.ops.{Path, exists, ls}
import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{CompilationResult, Dep, DepSyntax, TestModule}
import mill.T

trait ScalaNativeModule extends scalalib.ScalaModule { outer =>
  def scalaNativeVersion: T[String]
  //override def platformSuffix = T{ "_native" + scalaNativeVersion().split('.').take(2).mkString(".") }
  override def platformSuffix = T{ "_native" + scalaNativeVersion() }

  def scalaNativeBridgeVersion = T{ scalaNativeVersion() }

  def bridge = T.task{ ScalaNativeBridge.scalaNativeBridge().bridge(bridgeFullClassPath()) }

  def scalaNativeBridgeClasspath = T {
    val snBridgeKey = "MILL_SCALANATIVE_BRIDGE_" + scalaNativeBridgeVersion().replace('.', '_').replace('-', '_')
    val snBridgePath = sys.props(snBridgeKey)
    if (snBridgePath != null) Result.Success(
      Agg(PathRef(Path(snBridgePath), quick = true))
    ) else resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      "2.12.4",
      Seq(
        ivy"com.lihaoyi::mill-scalanativelib-scalanativebridges-${scalaNativeBridgeVersion()}:${sys.props("MILL_VERSION")}"
      )
    ).map(_.filter(_.path.toString.contains("mill-scalanativelib-scalanativebridges")))
  }

  def toolsIvyDeps = T{
    Seq(
      ivy"org.scala-native:tools_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:util_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:nir_2.12:${scalaNativeVersion()}"
    )
  }

  override def compileClasspath = T{
    upstreamRunClasspath() ++
      resources() ++
      unmanagedClasspath() ++
      resolveDeps(T.task{compileIvyDeps() ++ nativeIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def upstreamAssemblyClasspath = T{
    upstreamRunClasspath() ++
      unmanagedClasspath() ++
      resolveDeps(T.task{runIvyDeps() ++ nativeIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  def nativeLibIvy = T{ ivy"org.scala-native::nativelib::${scalaNativeVersion()}" }

  def nativeIvyDeps = T{
    Seq(nativeLibIvy()) ++
    Seq(
      ivy"org.scala-native::javalib::${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib::${scalaNativeVersion()}",
      ivy"org.scala-native::scalalib::${scalaNativeVersion()}"
    )
  }

  def bridgeFullClassPath = T {
    resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      scalaVersion(),
      toolsIvyDeps(),
      platformSuffix()
    ).map(t => (scalaNativeBridgeClasspath().toSeq ++ t.toSeq).map(_.path))
  }

  override def scalacPluginIvyDeps = Agg(ivy"org.scala-native:nscplugin_${scalaVersion()}:${scalaNativeVersion()}")

  def releaseMode = T { false }

  def nativeWorkdir = T{ T.ctx().dest }

  // Location of the clang compiler
  def nativeClang = T{
    val clang = bridge().llvmDiscover("clang", bridge().llvmClangVersions)
    bridge().llvmCheckThatClangIsRecentEnough(clang)
    clang
  }

  // Location of the clang++ compiler
  def nativeClangPP = T{
    val clang = bridge().llvmDiscover("clang++", bridge().llvmClangVersions)
    bridge().llvmCheckThatClangIsRecentEnough(clang)
    clang
  }

  // GC choice, either "none", "boehm" or "immix"
  def nativeGC = T{
    Option(System.getenv.get("SCALANATIVE_GC"))
      .getOrElse(bridge().defaultGarbageCollector)
  }

  def nativeTarget = T{ bridge().llvmDetectTarget(nativeClang(), nativeWorkdir()) }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = T{ bridge().llvmDefaultCompileOptions }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = T{ bridge().llvmDefaultLinkingOptions }

  // Whether to link `@stub` methods, or ignore them
  def nativeLinkStubs = T { false }


  def nativeLibJar = T{
    resolveDeps(T.task{Agg(nativeLibIvy())})()
      .filter{p => p.toString.contains("scala-native") && p.toString.contains("nativelib")}
      .toList
      .head
  }

  def nativeConfig = T.task {
    val classpath = runClasspath().map(_.path).filter(_.toIO.exists).toList

    bridge().config(
      nativeLibJar().path,
      finalMainClass(),
      classpath,
      nativeWorkdir(),
      nativeClang(),
      nativeClangPP(),
      nativeTarget(),
      nativeCompileOptions(),
      nativeLinkingOptions(),
      nativeGC(),
      nativeLinkStubs(),
      releaseMode())
  }

  def nativeLinkNIR = T.task{
    bridge().nativeLinkNIR(nativeConfig())
  }

  def nativeOptimizeNIR = T.task{
    bridge().nativeOptimizeNIR(nativeConfig(), nativeLinkNIR())
  }

  def nativeGenerateLL = T{
    bridge().nativeGenerateLL(nativeConfig(), nativeOptimizeNIR())
    val llFiles = ls.rec(nativeWorkdir()).filter(_.name.endsWith(".ll"))
    println(s"Produced ${llFiles.size} files")
    llFiles
  }

  // XXX copy the resulting paths to task dir so we can return PathRef?
  def nativeCompileLL = T{
    bridge().compileLL(nativeConfig(), nativeGenerateLL())
  }

  def nativeUnpackLib = T{
    bridge().unpackNativeLibrary(nativeLibJar().path, nativeWorkdir())
  }

  def nativeCompileLib = T{
    bridge().nativeCompileLib(nativeConfig(), nativeUnpackLib(), nativeWorkdir(), nativeLinkNIR())
  }

  def nativeLinkLL = T{
    bridge().linkLL(nativeConfig(), nativeLinkNIR(),  nativeCompileLL(), nativeCompileLib(), (T.ctx().dest / 'out))
  }

  // Generates native binary
  def nativeLink = T{ nativeLinkLL() }

  // Runs the native binary
  override def run(args: String*) = T.command{
    Jvm.baseInteractiveSubprocess(
      Vector(nativeLink().toString) ++ args,
      forkEnv(),
      workingDir = ammonite.ops.pwd)
  }

  // List all symbols not available at link time
  def nativeMissingDependencies = T{
    (nativeExternalDependencies().toSet -- nativeAvailableDependencies().toSet).toList.sorted
  }

  // List all symbols available at link time
  def nativeAvailableDependencies = T{
    bridge().nativeAvailableDependencies(runClasspath().map(_.path).toSeq)
  }

  // List all external dependencies at link time
  def nativeExternalDependencies = T{
    bridge().nativeExternalDependencies(compile().classes.path)
  }
}
