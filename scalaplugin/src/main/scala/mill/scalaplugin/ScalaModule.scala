package mill
package scalaplugin

import java.io.{File, PrintStream, PrintWriter, Writer}
import java.net.URLClassLoader
import java.util.Optional
import java.util.concurrent.Callable

import ammonite.ops._
import coursier.{Cache, Fetch, MavenRepository, Repository, Resolution}
import mill.define.Task
import mill.define.Task.{Module, TaskModule}
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar, subprocess}
import mill.util.Ctx
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.{InterfaceUtil, LogExchange}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.util.JsonFormatters._
import sbt.librarymanagement.DependencyResolution
import xsbti.GlobalLock



object ScalaModule{
  case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      am(classpathEntry)

    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  val compilerCache = new CompilerCache(2)
  def compileScala(scalaVersion: String,
                   sources: Seq[Path],
                   compileClasspath: Seq[Path],
                   compilerClasspath: Seq[Path],
                   compilerBridge: Seq[Path],
                   scalacOptions: Seq[String],
                   javacOptions: Seq[String])
                  (implicit ctx: Ctx): PathRef = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray

    def grepJar(classPath: Seq[Path], s: String) = {
      classPath
        .find(_.toString.endsWith(s))
        .getOrElse(throw new Exception("Cannot find " + s))
        .toIO
    }

    val outerClassLoader = getClass.getClassLoader
    val compilerJars = compilerClasspath.toArray.map(_.toIO)

    val compilerBridgeJar = new java.io.File(
//      s"bridge/${scalaVersion.replace('.', '_')}/target/scala-$binaryScalaVersion/mill-bridge_$scalaVersion-0.1-SNAPSHOT.jar"
      s"out/bridges/$scalaVersion/compile/classes"
    )

    val zincClassLoader = new URLClassLoader(compilerJars.map(_.toURI.toURL), null){
      override def loadClass(name: String): Class[_] = {
        Option(findLoadedClass(name)) orElse
        (try Some(findClass(name)) catch {case e: ClassNotFoundException => None}) getOrElse {
          // Try to limit `outerClassLoader` to only stuff from the compiler-bridge jar
          if (name.startsWith("xsbt.")) outerClassLoader.loadClass(name)
          else super.loadClass(name)
        }
      }
    }
    val scalaInstance = new ScalaInstance(
      version = scalaVersion,
      loader = zincClassLoader,
      libraryJar = grepJar(compilerClasspath, s"scala-library-$scalaVersion.jar"),
      compilerJar = grepJar(compilerClasspath, s"scala-compiler-$scalaVersion.jar"),
      allJars = compilerJars,
      explicitActual = None
    )

    val scalac = ZincUtil.scalaCompiler(scalaInstance, compilerBridgeJar)

    mkdir(ctx.dest)

    val ic = new sbt.internal.inc.IncrementalCompilerImpl()

    val logger = {

      val consoleAppender = MainAppender.defaultScreen(ConsoleOut.printStreamOut(
        ctx.log.outputStream
      ))
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

    val ignoreProgress = new CompileProgress {
      override def advance(current: Int, total: Int): Boolean = true
      override def startUnit(phase: String, unitPath: String): Unit = ()
    }

    val zincFile = (ctx.dest/'zinc).toIO
    val store = FileAnalysisStore.binary(zincFile)
    val classesDir = (ctx.dest / 'classes).toIO
    val newResult = ic.compile(
      ic.inputs(
        classpath = classesDir +: compileClasspathFiles,
        sources = sources.flatMap(ls.rec).filter(x => x.isFile && x.ext == "scala").map(_.toIO).toArray,
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
          new FreshCompilerCache,
//          compilerCache,
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

    zincClassLoader.close()

    store.set(
      AnalysisContents.create(
        newResult.analysis(),
        newResult.setup()
      )
    )

    PathRef(ctx.dest/'classes)
  }

  def resolveDependencies(repositories: Seq[Repository],
                          scalaVersion: String,
                          scalaBinaryVersion: String,
                          deps: Seq[Dep],
                          sources: Boolean = false): Seq[PathRef] = {
    val flattened = deps.map{
      case Dep.Java(dep) => dep
      case Dep.Scala(dep) =>
        dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaBinaryVersion))
      case Dep.Point(dep) =>
        dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaVersion))
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
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion)
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

  def forkWorkingDir = ammonite.ops.pwd
  def forkTest(args: String*) = T.command{
    val outputPath = tmp.dir()/"out.json"
    Jvm.subprocess(
      "mill.scalaplugin.TestRunner",
      getClass.getClassLoader.asInstanceOf[URLClassLoader].getURLs.toList.map(
        u => Path(new java.io.File(u.toURI))
      ),
      Seq(
        testFramework(),
        (runDepClasspath().map(_.path) :+ compile().path).mkString(" "),
        Seq(compile().path).mkString(" "),
        args.mkString(" "),
        outputPath.toString
      ),
      workingDir = forkWorkingDir
    )
    upickle.default.read[Option[String]](ammonite.ops.read(outputPath)) match{
      case Some(errMsg) => Result.Failure(errMsg)
      case None => Result.Success(())
    }
  }
  def test(args: String*) = T.command{
    TestRunner(
      testFramework(),
      runDepClasspath().map(_.path) :+ compile().path,
      Seq(compile().path),
      args
    ) match{
      case Some(errMsg) => Result.Failure(errMsg)
      case None => Result.Success(())
    }
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

  def resolveDeps(deps: Task[Seq[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      deps()
    )
  }
  def externalCompileDepClasspath = T{
    upstreamCompileDepClasspath().flatten ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())}
    )()
  }
  def externalCompileDepSources: T[Seq[PathRef]] = T{
    upstreamCompileDepSources().flatten ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())},
      sources = true
    )()
  }
  /**
    * Things that need to be on the classpath in order for this code to compile;
    * might be less than the runtime classpath
    */
  def compileDepClasspath: T[Seq[PathRef]] = T{
    upstreamCompileOutputClasspath() ++
    depClasspath() ++
    externalCompileDepClasspath()
  }

  /**
    * Strange compiler-bridge jar that the Zinc incremental compile needs
    */
  def compilerBridgeClasspath: T[Seq[PathRef]] = T{
    resolveDeps(
      T.task{Seq(Dep("org.scala-sbt", "compiler-bridge", "1.0.5"))},
    )()
  }

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Seq[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())},
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to run
    */
  def runDepClasspath: T[Seq[PathRef]] = T{
    upstreamRunClasspath().flatten ++
    depClasspath() ++
    resolveDeps(
      T.task{ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())},
    )()
  }

  def prependShellScript: T[String] = T{ "" }

  def sources = T.source{ basePath / 'src }
  def resources = T.source{ basePath / 'resources }
  def allSources = T{ Seq(sources()) }
  def compile = T.persistent{
    compileScala(
      scalaVersion(),
      allSources().map(_.path),
      compileDepClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      compilerBridgeClasspath().map(_.path),
      scalacOptions(),
      javacOptions()
    )
  }
  def assembly = T{
    createAssembly(
      (runDepClasspath().filter(_.path.ext != "pom") ++
      Seq(resources(), compile())).map(_.path).filter(exists),
      prependShellScript = prependShellScript()
    )
  }

  def classpath = T{ Seq(resources(), compile()) }
  def jar = T{
    createJar(Seq(resources(), compile()).map(_.path).filter(exists))
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
}
