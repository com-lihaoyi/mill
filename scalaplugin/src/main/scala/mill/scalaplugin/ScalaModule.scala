package mill
package scalaplugin

import java.io.File
import java.util.Optional

import ammonite.ops._
import coursier.{Cache, Fetch, MavenRepository, Repository, Resolution}
import mill.define.Task
import mill.define.Task.{Module, TaskModule}
import mill.eval.{Evaluator, PathRef}
import mill.modules.Jvm.{createAssembly, createJar}
import mill.util.OSet
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
                   outputPath: Path): PathRef = {
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
      allJars = compileClasspath.toArray.map(_.toIO),
      explicitActual = None
    )
    val scalac = ZincUtil.scalaCompiler(
      scalaInstance,
      grepJar(s"compiler-bridge_$binaryScalaVersion-1.0.3.jar")
    )

    mkdir(outputPath)

    val ic = new sbt.internal.inc.IncrementalCompilerImpl()

    val logger = {
      val console = ConsoleOut.systemOut
      val consoleAppender = MainAppender.defaultScreen(console)
      val l = LogExchange.logger("Hello")
      LogExchange.unbindLoggerAppenders("Hello")
      LogExchange.bindLoggerAppenders("Hello", (consoleAppender -> sbt.util.Level.Warn) :: Nil)
      l
    }
    val compiler = new IncrementalCompilerImpl


    val cs = compiler.compilers(scalaInstance, ClasspathOptionsUtil.boot, None, scalac)

    val lookup = MockedLookup(Function.const(Optional.empty[CompileAnalysis]))
    val reporter = new ManagedLoggedReporter(10, logger)
    val extra = Array(InterfaceUtil.t2(("key", "value")))

    var lastCompiledUnits: Set[String] = Set.empty
    val progress = new CompileProgress {
      override def advance(current: Int, total: Int): Boolean = true

      override def startUnit(phase: String, unitPath: String): Unit = {
        println(unitPath)
        lastCompiledUnits += unitPath
      }
    }

    println("Running Compile")
    println(outputPath/'zinc)
    println(exists(outputPath/'zinc))
    val store = FileAnalysisStore.binary((outputPath/'zinc).toIO)
    val newResult = ic.compile(
      ic.inputs(
        classpath = compileClasspath.map(_.toIO).toArray,
        sources = ls.rec(sources).filter(_.isFile).map(_.toIO).toArray,
        classesDirectory = (outputPath / 'classes).toIO,
        scalacOptions = Array(),
        javacOptions = Array(),
        maxErrors = 10,
        sourcePositionMappers = Array(),
        order = CompileOrder.Mixed,
        compilers = cs,
        setup = ic.setup(
          lookup,
          skip = false,
          (outputPath/'zinc_cache).toIO,
          compilerCache,
          IncOptions.of(),
          reporter,
          Some(progress),
          extra
        ),
        pr = {
          val prev = store.get()
          println(prev)
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
    Dep("org.scala-sbt", s"compiler-bridge", "1.0.3")
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
    compileScala(scalaVersion(), sources().path, compileDepClasspath().map(_.path), T.ctx().dest)
  }
  def assembly = T{
    val dest = T.ctx().dest
    createAssembly(
      dest,
      (runDepClasspath().filter(_.path.ext != "pom") ++ Seq(resources(), compile())).map(_.path).filter(exists),
      prependShellScript = prependShellScript()
    )
    PathRef(dest)
  }

  def classpath = T{ Seq(resources(), compile()) }
  def jar = T{
    val dest = T.ctx().dest
    createJar(dest, Seq(resources(), compile()).map(_.path).filter(exists))
    PathRef(dest)
  }

  def run(mainClass: String) = T.command{
    import ammonite.ops._, ImplicitWd._
    %('java, "-cp", (runDepClasspath().map(_.path) :+ compile().path).mkString(":"), mainClass)
  }

  def console() = T.command{
    import ammonite.ops._, ImplicitWd._
    %('java,
      "-cp",
      (runDepClasspath().map(_.path) :+ compile().path).mkString(":"),
      "scala.tools.nsc.MainGenericRunner",
      "-usejavacp"
    )
  }
}
