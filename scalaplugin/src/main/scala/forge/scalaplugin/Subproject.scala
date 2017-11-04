package forge
package scalaplugin

import java.io.File

import ammonite.ops.{Path, ls, mkdir, pwd}
import coursier.{Cache, Dependency, Fetch, MavenRepository, Module, Repository, Resolution}
import forge.{Target => T}
import forge.util.PathRef
import sbt.internal.inc.{FreshCompilerCache, ScalaInstance, ZincUtil}
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import xsbti.api.{ClassLike, DependencyContext}
import xsbti.compile.DependencyChanges

import scalaz.concurrent.Task
object Subproject{
  def compileScala(scalaVersion: T[String],
                   sources: T[PathRef],
                   compileClasspath: T[Seq[PathRef]],
                   outputPath: T[Path]): T[PathRef] = {
    for((scalaVersion, sources, compileClasspath, outputPath) <- zip(scalaVersion, sources, compileClasspath, outputPath))
    yield {
      val binaryScalaVersion = scalaVersion.split('.').dropRight(1).mkString(".")
      def grepJar(s: String) = {
        compileClasspath
          .find(_.path.toString.endsWith(s))
          .getOrElse(throw new Exception("Cannot find " + s))
          .path
          .toIO
      }
      val scalac = ZincUtil.scalaCompiler(
        new ScalaInstance(
          version = scalaVersion,
          loader = getClass.getClassLoader,
          libraryJar = grepJar(s"scala-library-$scalaVersion.jar"),
          compilerJar = grepJar(s"scala-compiler-$scalaVersion.jar"),
          allJars = compileClasspath.toArray.map(_.path.toIO),
          explicitActual = None
        ),
        grepJar(s"compiler-bridge_$binaryScalaVersion-1.0.3.jar")
      )

      mkdir(outputPath)


      scalac.apply(
        sources = ls.rec(sources.path).filter(_.isFile).map(_.toIO).toArray,
        changes = new DependencyChanges {
          def isEmpty = true
          def modifiedBinaries() = Array[File]()
          def modifiedClasses() = Array[String]()
        },
        classpath = compileClasspath.map(_.path.toIO).toArray,
        singleOutput = outputPath.toIO,
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
      PathRef(outputPath)
    }
  }
  def createJar(sourceDirs: T[Seq[PathRef]]) = ???
  def resolveDependencies(repositories: Seq[Repository],
                          deps: Seq[coursier.Dependency]): Seq[PathRef] = {
    val start = Resolution(deps.toSet)
    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val localArtifacts: Seq[File] = Task.gatherUnordered(
      resolution.artifacts.map(Cache.file(_).run)
    ).unsafePerformSync.flatMap(_.toOption)

    localArtifacts.map(p => PathRef(Path(p)))
  }
  def scalaCompilerIvyDeps(scalaVersion: String, scalaBinaryVersion: String) = Seq(
    Dependency(Module("org.scala-lang", "scala-compiler"), scalaVersion),
    Dependency(Module("org.scala-sbt", s"compiler-bridge_$scalaBinaryVersion"), "1.0.3")
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Seq(
    Dependency(Module("org.scala-lang", "scala-library"), scalaVersion)
  )
}
import Subproject._

abstract class Subproject {
  val scalaVersion: T[String]

  val scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  val ivyDeps = T{ Seq[coursier.Dependency]() }
  val compileIvyDeps = T{ Seq[coursier.Dependency]() }
  val runIvyDeps = T{ Seq[coursier.Dependency]() }
  val basePath: T[Path]

  val repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  val depClasspath = T{ Seq.empty[PathRef] }
  val compileDepClasspath = T[Seq[PathRef]] {
    depClasspath() ++ resolveDependencies(
      repositories,
      ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion(), scalaBinaryVersion())
    )
  }

  val runDepClasspath =  T[Seq[PathRef]] {
    depClasspath() ++ resolveDependencies(
      repositories,
      ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())
    )
  }

  val sources = T{ PathRef(basePath() / 'src) }
  val outputPath = T{ basePath() / 'out }
  val resources = T{ PathRef(basePath() / 'resources) }
  val compiledPath = T{ outputPath() / 'classpath }
  val compiled = T{
    compileScala(scalaVersion, sources, compileDepClasspath, outputPath)
  }

  val classpath = T{ Seq(resources(), compiled()) }
//  val jar = T{ createJar(classpath) }
}
