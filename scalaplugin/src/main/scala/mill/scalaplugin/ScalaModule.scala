package mill
package scalaplugin

import java.io.File
import java.util.Optional

import ammonite.ops._
import coursier.{Cache, Fetch, MavenRepository, Repository, Resolution}
import mill.define.Task
import mill.define.Task.{Module, TaskModule}
import mill.eval.PathRef
import mill.modules.Jvm.{createAssembly, createJar, subprocess}
import mill.scalaplugin.publish.Dependency
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.{InterfaceUtil, LogExchange}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}




object ScalaModule{
  case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      am(classpathEntry)

    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  val compilerCache = new CompilerCache(10)
  def compileScala(scalaVersion: String,
                   sources: Path,
                   compileClasspath: Seq[Path],
                   scalacOptions: Seq[String],
                   javacOptions: Seq[String],
                   outputPath: Path): PathRef = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray
    val binaryScalaVersion = scalaVersion.split('.').dropRight(1).mkString(".")
    def grepJar(s: String) = {
      compileClasspath
        .find(_.toString.endsWith(s))
        .getOrElse(throw new Exception("Cannot find " + s))
        .toIO
    }
    val scalaInstance = new ScalaInstance(
      version = scalaVersion,
      loader = getClass.getClassLoader,
      libraryJar = grepJar(s"scala-library-$scalaVersion.jar"),
      compilerJar = grepJar(s"scala-compiler-$scalaVersion.jar"),
      allJars = compileClasspathFiles,
      explicitActual = None
    )
    val scalac = ZincUtil.scalaCompiler(
      scalaInstance,
      grepJar(s"compiler-bridge_$binaryScalaVersion-1.0.5.jar")
    )

    mkdir(outputPath)

    val ic = new sbt.internal.inc.IncrementalCompilerImpl()

    val logger = {
      val console = ConsoleOut.systemOut
      val consoleAppender = MainAppender.defaultScreen(console)
      val l = LogExchange.logger("Hello")
      LogExchange.unbindLoggerAppenders("Hello")
      LogExchange.bindLoggerAppenders("Hello", (consoleAppender -> sbt.util.Level.Info) :: Nil)
      l
    }
    val compiler = new IncrementalCompilerImpl


    val cs = compiler.compilers(scalaInstance, ClasspathOptionsUtil.boot, None, scalac)

    val lookup = MockedLookup(Function.const(Optional.empty[CompileAnalysis]))
    val reporter = new ManagedLoggedReporter(10, logger)
    val extra = Array(InterfaceUtil.t2(("key", "value")))

    var lastCompiledUnits: Set[String] = Set.empty
    val ignoreProgress = new CompileProgress {
      override def advance(current: Int, total: Int): Boolean = true
      override def startUnit(phase: String, unitPath: String): Unit = ()
    }

    val zincFile = (outputPath/'zinc).toIO
    val store = FileAnalysisStore.binary(zincFile)
    val classesDir = (outputPath / 'classes).toIO
    val newResult = ic.compile(
      ic.inputs(
        classpath = classesDir +: compileClasspathFiles,
        sources = ls.rec(sources).filter(_.isFile).map(_.toIO).toArray,
        classesDirectory = classesDir,
        scalacOptions = scalacOptions.toArray,
        javacOptions = javacOptions.toArray,
        maxErrors = 10,
        sourcePositionMappers = Array(),
        order = CompileOrder.Mixed,
        compilers = cs,
        setup = ic.setup(
          lookup,
          skip = false,
          zincFile,
          compilerCache,
          IncOptions.of(),
          reporter,
          Some(ignoreProgress),
          extra
        ),
        pr = {
          val prev = store.get()
          PreviousResult.of(prev.map(_.getAnalysis), prev.map(_.getMiniSetup))
        }
      ),
      logger = logger
    )

    store.set(
      AnalysisContents.create(
        newResult.analysis(),
        newResult.setup()
      )
    )

    PathRef(outputPath/'classes)
  }

  def resolveDependencies(repositories: Seq[Repository],
                          scalaVersion: String,
                          scalaBinaryVersion: String,
                          deps: Seq[Dep],
                          sources: Boolean = false): Seq[PathRef] = {
    val flattened = deps.map{
      case Dep.Java(dep) => dep
      case Dep.Scala(dep) => dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaBinaryVersion))
      case Dep.Point(dep) => dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaVersion))
    }.toSet
    val start = Resolution(flattened)

    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val sourceOrJar =
      if (sources) resolution.classifiersArtifacts(Seq("sources"))
      else resolution.artifacts
    val localArtifacts: Seq[File] = scalaz.concurrent.Task
      .gatherUnordered(sourceOrJar.map(Cache.file(_).run))
      .unsafePerformSync
      .flatMap(_.toOption)

    localArtifacts.map(p => PathRef(Path(p), quick = true))
  }
  def scalaCompilerIvyDeps(scalaVersion: String) = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion),
    Dep("org.scala-sbt", "compiler-bridge", "1.0.5")
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Seq[Dep](
    Dep.Java("org.scala-lang", "scala-library", scalaVersion)
  )

  val DefaultShellScript: Seq[String] = Seq(
    "#!/usr/bin/env sh",
    "exec java -jar \"$0\" \"$@\""
  )
}
import ScalaModule._
trait TestScalaModule extends ScalaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFramework: T[String]
  def test(args: String*) = T.command{
    TestRunner(
      testFramework(),
      runDepClasspath().map(_.path) :+ compile().path,
      Seq(compile().path),
      args
    )
  }
}
trait ScalaModule extends Module with TaskModule{ outer =>
  def defaultCommandName() = "run"
  trait Tests extends TestScalaModule{
    def scalaVersion = outer.scalaVersion()
    override def projectDeps = Seq(outer)
  }
  def scalaVersion: T[String]

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ Seq[Dep]() }
  def compileIvyDeps = T{ Seq[Dep]() }
  def runIvyDeps = T{ Seq[Dep]() }
  def basePath: Path

  def scalacOptions = T{ Seq.empty[String] }
  def javacOptions = T{ Seq.empty[String] }

  val repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def projectDeps = Seq.empty[ScalaModule]
  def depClasspath = T{ Seq.empty[PathRef] }


  def upstreamRunClasspath = T{
    Task.traverse(
      for (p <- projectDeps)
        yield T.task(p.runDepClasspath() ++ Seq(p.compile()))
    )
  }

  def upstreamCompileDepClasspath = T{
    Task.traverse(projectDeps.map(_.compileDepClasspath))
  }
  def upstreamCompileDepSources = T{
    Task.traverse(projectDeps.map(_.externalCompileDepSources))
  }
  def upstreamCompileOutputClasspath = T{
    Task.traverse(projectDeps.map(_.compile))
  }

  def externalCompileDepClasspath = T{
    upstreamCompileDepClasspath().flatten ++
      resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())
      )
  }
  def externalCompileDepSources: T[Seq[PathRef]] = T{
    upstreamCompileDepSources().flatten ++
      resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion()),
        sources = true
      )
  }
  def compileDepClasspath: T[Seq[PathRef]] = T{
    upstreamCompileOutputClasspath() ++
      depClasspath() ++
      externalCompileDepClasspath()
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

  def prependShellScript: T[String] = T{ "" }

  def sources = T.source{ basePath / 'src }
  def resources = T.source{ basePath / 'resources }
  def compile = T.persistent{
    compileScala(
      scalaVersion(),
      sources().path,
      compileDepClasspath().map(_.path),
      scalacOptions(),
      javacOptions(),
      T.ctx().dest)
  }
  def assembly = T{
    val outDir = T.ctx().dest/up
    val n = name()
    val v = version()
    val jarName = s"${n}-${v}.jar"
    val dest = outDir/jarName
    createAssembly(
      dest,
      (runDepClasspath().filter(_.path.ext != "pom") ++ Seq(resources(), compile())).map(_.path).filter(exists),
      prependShellScript = prependShellScript()
    )
    PathRef(dest)
  }

  def classpath = T{ Seq(resources(), compile()) }
  def jar = T{
    val outDir = T.ctx().dest/up
    val n = name()
    val v = version()
    val jarName = s"${n}-${v}.jar"
    val dest = outDir/jarName
    createJar(dest, Seq(resources(), compile()).map(_.path).filter(exists))
    PathRef(dest)
  }

  def run(mainClass: String) = T.command{
    subprocess(mainClass, runDepClasspath().map(_.path) :+ compile().path)
  }

  def console() = T.command{
    subprocess(
      mainClass = "scala.tools.nsc.MainGenericRunner",
      classPath = externalCompileDepClasspath().map(_.path) :+ compile().path,
      options = Seq("-usejavacp")
    )
  }

  def organization: T[String]
  def name: T[String]
  def version: T[String]
  // build artifact name as "mill-2.12.4" instead of "mill-2.12"
  def useFullScalaVersionForPublish: T[Boolean] = T { false }
  //def crossScalaVersions: T[Seq[String]] = T { Seq (scalaBinaryVersion()) }

//  def pomSettings = T {
//    import publish._
//    PomSettings("mill", "url", Seq.empty, SCM("", ""), Seq.empty)
//  }

  def publishLocal() = T.command {
    import publish._

    println("Building jar")
    val file = jar()
    val scalaFull = scalaVersion()
    val scalaBin = scalaBinaryVersion()
    val useFullVersion = useFullScalaVersionForPublish()
    val deps = ivyDeps()
    //TODO: should we check that dependencies are exists for all cross versions?

    val dependencies = deps.map(d => PublishU.convertDep(d, scalaFull, scalaBin))
    val artScalaVersion = if (useFullVersion) scalaFull else scalaBin
    val artifact = ScalaArtifact(organization(), name(), version(), artScalaVersion)
    val r = LocalPublisher.publish(file, artifact, dependencies)
    println("DONE")
  }


}

object PublishU {

  import publish._

  //TODO more scopes
  def convertDep(dep: Dep, scalaFull: String, scalaBin: String): Dependency = {
    dep match {
      case d: Dep.Java =>
        import d.dep._
        val art = JavaArtifact(module.organization, module.name, version)
        Dependency(art, Scope.Compile)
      case d: Dep.Scala =>
        import d.dep._
        val art = ScalaArtifact(module.organization, module.name, version, scalaBin)
        Dependency(art, Scope.Compile)
      case d: Dep.Point =>
        import d.dep._
        val art = ScalaArtifact(module.organization, module.name, version, scalaFull)
        Dependency(art, Scope.Compile)
    }
  }

}
