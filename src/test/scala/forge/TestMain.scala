package forge
import ammonite.ops._
import java.io.File

import coursier._
import sbt.internal.inc.{FreshCompilerCache, ScalaInstance, ZincUtil}
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import xsbti.api.{ClassLike, DependencyContext}
import xsbti.compile._

import scalaz.concurrent.Task

object TestMain {
  def main(args: Array[String]): Unit = {
    val scalaVersion = "2.12.4"
    val start = Resolution(
      Set(
        Dependency(Module("org.scala-lang", "scala-reflect"), scalaVersion),
        Dependency(Module("org.scala-lang", "scala-compiler"), scalaVersion),
        Dependency(Module("org.scala-lang", "scala-reflect"), scalaVersion),
        Dependency(Module("org.scala-sbt", "compiler-bridge_2.12"), "1.0.3"),
        Dependency(Module("com.lihaoyi", "sourcecode_2.12"), "0.1.4"),
        Dependency(Module("com.lihaoyi", "pprint_2.12"), "0.5.3"),
        Dependency(Module("com.lihaoyi", "ammonite_2.12.4"), "1.0.3"),
        Dependency(Module("com.typesafe.play", "play-json_2.12"), "2.6.6"),
        Dependency(Module("org.scala-sbt", "zinc_2.12"), "1.0.3")
      )
    )
    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2")
    )

    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync


    val localArtifacts: Seq[File] = Task.gatherUnordered(
      resolution.artifacts.map(Cache.file(_).run)
    ).unsafePerformSync.flatMap(_.toOption)

    pprint.log(localArtifacts)
    def grepJar(s: String) = localArtifacts.find(_.toString.endsWith(s)).get

    val scalac = ZincUtil.scalaCompiler(
      new ScalaInstance(
        version = scalaVersion,
        loader = getClass.getClassLoader,
        libraryJar = grepJar(s"scala-library-$scalaVersion.jar"),
        compilerJar = grepJar(s"scala-compiler-$scalaVersion.jar"),
        allJars = localArtifacts.toArray,
        explicitActual = None
      ),
      grepJar("compiler-bridge_2.12-1.0.3.jar")
    )

    val outputDir = pwd/'target/'zinc
    mkdir(outputDir)
    val scalaFiles = ls.rec(pwd/'src/'main/'scala/'forge).filter(_.ext == "scala").map(_.toIO).toArray

    pprint.log(scalaFiles)
    scalac.apply(
      sources = scalaFiles,
      changes = new DependencyChanges {
        def isEmpty = true
        def modifiedBinaries() = Array[File]()
        def modifiedClasses() = Array[String]()
      },
      classpath = localArtifacts.toArray,
      singleOutput = outputDir.toIO,
      options = Array(),
      callback = new xsbti.AnalysisCallback {
        def startSource(source: File) = ()
        def apiPhaseCompleted() = ()
        def enabled() = true
        def binaryDependency(onBinaryEntry: File, onBinaryClassName: String, fromClassName: String, fromSourceFile: File, context: DependencyContext) = ()
        def generatedNonLocalClass(source: File, classFile: File, binaryClassName: String, srcClassName: String) = ()
        def problem(what: String, pos: xsbti.Position, msg: String, severity: xsbti.Severity, reported: Boolean) = ()
        def dependencyPhaseCompleted() = ()
        def classDependency(onClassName: String, sourceClassName: String, context: DependencyContext) = ()
        def generatedLocalClass(source: File, classFile: File) = ()
        def api(sourceFile: File, classApi: ClassLike) = ()

        def mainClass(sourceFile: File, className: String) = ()
        def usedName(className: String, name: String, useScopes: java.util.EnumSet[xsbti.UseScope]) = ()
      },
      maximumErrors = 10,
      cache = new FreshCompilerCache(),
      log = {
        val console = ConsoleOut.systemOut
        val consoleAppender = MainAppender.defaultScreen(console)
        val l = LogExchange.logger("Hello")
        LogExchange.unbindLoggerAppenders("Hello")
        LogExchange.bindLoggerAppenders("Hello", (consoleAppender -> sbt.util.Level.Warn) :: Nil)
        l
      }
    )
  }
}
