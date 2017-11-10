package forge
package scalaplugin

import java.io.File

import ammonite.ops.{Path, ls, mkdir, pwd}
import coursier.{Cache, Dependency, Fetch, MavenRepository, Module, Repository, Resolution}
import forge.define.Task
import forge.define.Task.Cacher
import forge.eval.PathRef
import forge.util.Args
import play.api.libs.json._
import sbt.internal.inc.{FreshCompilerCache, ScalaInstance, ZincUtil}
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import xsbti.api.{ClassLike, DependencyContext}
import xsbti.compile.DependencyChanges

object Subproject{
  def compileScala(scalaVersion: String,
                   sources: PathRef,
                   compileClasspath: Seq[PathRef],
                   outputPath: Path): PathRef = {
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

  def resolveDependencies(repositories: Seq[Repository],
                          scalaVersion: String,
                          scalaBinaryVersion: String,
                          deps: Seq[ScalaDep]): Seq[PathRef] = {
    val flattened = deps.map{
      case ScalaDep.Java(dep) => dep
      case ScalaDep.Scala(dep) => dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaBinaryVersion))
      case ScalaDep.Point(dep) => dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaVersion))
    }.toSet
    val start = Resolution(flattened)

    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val localArtifacts: Seq[File] = scalaz.concurrent.Task
      .gatherUnordered(resolution.artifacts.map(Cache.file(_).run))
      .unsafePerformSync
      .flatMap(_.toOption)

    localArtifacts.map(p => PathRef(Path(p)))
  }
  def scalaCompilerIvyDeps(scalaVersion: String) = Seq[ScalaDep](
    Dependency(Module("org.scala-lang", "scala-compiler"), scalaVersion),
    ScalaDep.Scala(Dependency(Module("org.scala-sbt", s"compiler-bridge"), "1.0.3"))
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Seq[ScalaDep](
    Dependency(Module("org.scala-lang", "scala-library"), scalaVersion)
  )
  sealed trait ScalaDep
  object ScalaDep{
    case class Java(dep: coursier.Dependency) extends ScalaDep
    implicit def default(dep: coursier.Dependency): ScalaDep = new Java(dep)
    def apply(dep: coursier.Dependency) = Scala(dep)
    case class Scala(dep: coursier.Dependency) extends ScalaDep
    case class Point(dep: coursier.Dependency) extends ScalaDep
    implicit def formatter: Format[ScalaDep] = new Format[ScalaDep]{
      def writes(o: ScalaDep) = o match{
        case Java(dep) => Json.obj("Java" -> Json.toJson(dep))
        case Scala(dep) => Json.obj("Scala" -> Json.toJson(dep))
        case Point(dep) => Json.obj("PointScala" -> Json.toJson(dep))
      }

      def reads(json: JsValue) = json match{
        case obj: JsObject =>
          obj.fields match{
            case Seq(("Java", dep)) => Json.fromJson[coursier.Dependency](dep).map(Java)
            case Seq(("Scala", dep)) => Json.fromJson[coursier.Dependency](dep).map(Scala)
            case Seq(("PointScala", dep)) => Json.fromJson[coursier.Dependency](dep).map(Point)
            case _ => JsError("Invalid JSON object to parse ScalaDep")
          }


        case _ => JsError("Expected JSON object to parse ScalaDep")
      }
    }
  }
}
import Subproject._

abstract class Subproject extends Cacher{
  def scalaVersion: T[String]

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ Seq[ScalaDep]() }
  def compileIvyDeps = T{ Seq[ScalaDep]() }
  def runIvyDeps = T{ Seq[ScalaDep]() }
  def basePath: T[Path]

  val repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def depClasspath = T{ Seq.empty[PathRef] }
  def compileDepClasspath = T[Seq[PathRef]] {
    depClasspath() ++ resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())
    )
  }

  def runDepClasspath =  T[Seq[PathRef]] {
    depClasspath() ++ resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())
    )
  }

  def sources = T{ PathRef(basePath() / 'src) }
  def resources = T{ PathRef(basePath() / 'resources) }
  def compiled = T{
    compileScala(scalaVersion(), sources(), compileDepClasspath(), Task.ctx().dest)
  }

  def classpath = T{ Seq(resources(), compiled()) }
  def jar = T{ modules.Jvm.jarUp(resources, compiled) }

  def run(mainClass: String) = T.command{
    import ammonite.ops._, ImplicitWd._
    %('java, "-cp", (runDepClasspath().map(_.path) :+ compiled()).mkString(":"), mainClass)
  }

  def console() = T.command{
    import ammonite.ops._, ImplicitWd._
    %('java,
      "-cp",
      (runDepClasspath().map(_.path) :+ compiled()).mkString(":"),
      "scala.tools.nsc.MainGenericRunner",
      "-usejavacp"
    )
  }
}
