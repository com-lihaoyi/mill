package mill
package scalaplugin

import java.io.File
import java.lang.annotation.Annotation
import java.net.URLClassLoader

import ammonite.ops.{Path, ls, mkdir, pwd, up}
import coursier.{Cache, Dependency, Fetch, MavenRepository, Module, Repository, Resolution}
import mill.define.Task
import mill.define.Task.Cacher
import mill.eval.PathRef
import mill.util.Args
import play.api.libs.json._
import sbt.internal.inc.{FreshCompilerCache, ScalaInstance, ZincUtil}
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.testing.{AnnotatedFingerprint, SubclassFingerprint}
import sbt.util.LogExchange
import xsbti.api.{ClassLike, DependencyContext}
import xsbti.compile.DependencyChanges



object Subproject{
  def runTests(frameworkName: String,
               testClassloader: URLClassLoader) = {
    val framework = Class.forName(frameworkName)
      .newInstance()
      .asInstanceOf[sbt.testing.Framework]

    val fingerprints = framework.fingerprints()
    val testClasses = for{
      url <- testClassloader.getURLs
      path <- ls.rec(ammonite.ops.Path(url.getFile)).toIterator
      if path.ext == "class"
      className = (path/up/path.last.stripSuffix(".class")).segments.mkString(".")
      cls = testClassloader.loadClass(className)
      if fingerprints.exists{
        case f: SubclassFingerprint =>
          cls.isAssignableFrom(cls)
        case f: AnnotatedFingerprint =>
          cls.isAnnotationPresent(
            Class.forName(f.annotationName()).asInstanceOf[Class[Annotation]]
          )
      }
    } yield cls


  }
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
                          deps: Seq[Dep]): Seq[PathRef] = {
    val flattened = deps.map{
      case Dep.Java(dep) => dep
      case Dep.Scala(dep) => dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaBinaryVersion))
      case Dep.Point(dep) => dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaVersion))
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
  def scalaCompilerIvyDeps(scalaVersion: String) = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion),
    Dep("org.scala-sbt", s"compiler-bridge", "1.0.3")
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Seq[Dep](
    Dep.Java("org.scala-lang", "scala-library", scalaVersion)
  )
}
import Subproject._

trait Subproject extends Cacher{
  def scalaVersion: T[String]

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ Seq[Dep]() }
  def compileIvyDeps = T{ Seq[Dep]() }
  def runIvyDeps = T{ Seq[Dep]() }
  def basePath: T[Path]

  val repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def projectDeps = Seq.empty[Subproject]
  def depClasspath = T{ Seq.empty[PathRef] }


  def upstreamRunClasspath = Task.traverse(
    for (p <- projectDeps)
    yield T.task(p.runDepClasspath() ++ Seq(p.compiled()))
  )

  def upstreamCompileClasspath = Task.traverse(
    for (p <- projectDeps)
    yield T.task(p.compileDepClasspath() ++ Seq(p.compiled()))
  )

  def compileDepClasspath: T[Seq[PathRef]] = T{
    upstreamCompileClasspath().flatten ++ depClasspath() ++ resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())
    )
  }

  def runDepClasspath: T[Seq[PathRef]] = T{
    upstreamRunClasspath().flatten ++
    depClasspath() ++
    resolveDependencies(
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
    %('java, "-cp", (runDepClasspath().map(_.path) :+ compiled().path).mkString(":"), mainClass)
  }

  def console() = T.command{
    import ammonite.ops._, ImplicitWd._
    %('java,
      "-cp",
      (runDepClasspath().map(_.path) :+ compiled().path).mkString(":"),
      "scala.tools.nsc.MainGenericRunner",
      "-usejavacp"
    )
  }
}
