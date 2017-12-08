package mill
package scalaplugin

import java.io.File
import java.net.URLClassLoader
import java.util.Optional

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

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] = upickle.default.macroRW
}

// analysisFile is represented by Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: Path, classes: PathRef)

object ScalaModule{
  case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      am(classpathEntry)

    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  var scalaInstanceCache = Option.empty[(Long, ScalaInstance)]

  def compileScala(scalaVersion: String,
                   sources: Seq[Path],
                   compileClasspath: Seq[Path],
                   compilerClasspath: Seq[Path],
                   compilerBridge: Seq[Path],
                   scalacOptions: Seq[String],
                   scalacPluginClasspath: Seq[Path],
                   javacOptions: Seq[String],
                   upstreamCompileOutput: Seq[CompilationResult])
                  (implicit ctx: Ctx): CompilationResult = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray

    def grepJar(classPath: Seq[Path], s: String) = {
      classPath
        .find(_.toString.endsWith(s))
        .getOrElse(throw new Exception("Cannot find " + s))
        .toIO
    }

    val compilerJars = compilerClasspath.toArray.map(_.toIO)
    val compilerBridgeKey = "MILL_COMPILER_BRIDGE_"+scalaVersion.replace('.', '_')
    val compilerBridgePath = sys.props(compilerBridgeKey)
    assert(compilerBridgePath != null, "Cannot find compiler bridge " + compilerBridgeKey)
    val compilerBridgeJar = new java.io.File(compilerBridgePath)

    val classloaderSig = compilerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum

    val scalaInstance = scalaInstanceCache match{
      case Some((k, v)) if k == classloaderSig => v
      case _ =>
        val scalaInstance = new ScalaInstance(
          version = scalaVersion,
          loader = new URLClassLoader(compilerJars.map(_.toURI.toURL), null),
          libraryJar = grepJar(compilerClasspath, s"scala-library-$scalaVersion.jar"),
          compilerJar = grepJar(compilerClasspath, s"scala-compiler-$scalaVersion.jar"),
          allJars = compilerJars,
          explicitActual = None
        )
        scalaInstanceCache = Some((classloaderSig, scalaInstance))
        scalaInstance
    }

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

    def analysisMap(f: File): Optional[CompileAnalysis] = {
      if (f.isFile) {
        Optional.empty[CompileAnalysis]
      } else {
        upstreamCompileOutput.collectFirst {
          case CompilationResult(zincPath, classFiles) if classFiles.path.toNIO == f.toPath =>
            FileAnalysisStore.binary(zincPath.toIO).get().map[CompileAnalysis](_.getAnalysis)
        }.getOrElse(Optional.empty[CompileAnalysis])
      }
    }

    val lookup = MockedLookup(analysisMap)

    val zincFile = ctx.dest / 'zinc
    val classesDir = ctx.dest / 'classes

    val zincIOFile = zincFile.toIO
    val classesIODir = classesDir.toIO

    val store = FileAnalysisStore.binary(zincIOFile)

    val newResult = ic.compile(
      ic.inputs(
        classpath = classesIODir +: compileClasspathFiles,
        sources = sources.flatMap(ls.rec).filter(x => x.isFile && x.ext == "scala").map(_.toIO).toArray,
        classesDirectory = classesIODir,
        scalacOptions = (scalacPluginClasspath.map(jar => s"-Xplugin:${jar}") ++  scalacOptions).toArray,
        javacOptions = javacOptions.toArray,
        maxErrors = 10,
        sourcePositionMappers = Array(),
        order = CompileOrder.Mixed,
        compilers = ic.compilers(
          scalaInstance,
          ClasspathOptionsUtil.boot,
          None,
          ZincUtil.scalaCompiler(scalaInstance, compilerBridgeJar)
        ),
        setup = ic.setup(
          lookup,
          skip = false,
          zincIOFile,
          new FreshCompilerCache,
          IncOptions.of(),
          new ManagedLoggedReporter(10, logger),
          None,
          Array()
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

    CompilationResult(zincFile, PathRef(classesDir))
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
  def forkArgs = T{ Seq.empty[String] }
  def forkTest(args: String*) = T.command{
    val outputPath = tmp.dir()/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalaplugin.TestRunner",
      classPath = Jvm.gatherClassloaderJars(),
      jvmOptions = forkArgs(),
      options = Seq(
        testFramework(),
        (runDepClasspath().map(_.path) :+ compile().classes.path).mkString(" "),
        Seq(compile().classes.path).mkString(" "),
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
      runDepClasspath().map(_.path) :+ compile().classes.path,
      Seq(compile().classes.path),
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
  def mainClass: T[Option[String]] = None

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ Seq[Dep]() }
  def compileIvyDeps = T{ Seq[Dep]() }
  def scalacPluginIvyDeps = T{ Seq[Dep]() }
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
      yield T.task(p.runDepClasspath() ++ Seq(p.compile().classes))
    )
  }

  def upstreamCompileDepClasspath = T{
    Task.traverse(projectDeps.map(_.compileDepClasspath))
  }
  def upstreamCompileDepSources = T{
    Task.traverse(projectDeps.map(_.externalCompileDepSources))
  }

  def upstreamCompileOutput = T{
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
    upstreamCompileOutput().map(_.classes) ++
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

  def scalacPluginClasspath: T[Seq[PathRef]] =
    resolveDeps(
      T.task{scalacPluginIvyDeps()}
    )()

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Seq[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())},
    )() ++ scalacPluginClasspath()
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
  def compile: T[CompilationResult] = T.persistent{
    compileScala(
      scalaVersion(),
      allSources().map(_.path),
      compileDepClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      compilerBridgeClasspath().map(_.path),
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }
  def assembly = T{
    createAssembly(
      (runDepClasspath().filter(_.path.ext != "pom") ++
      Seq(resources(), compile().classes)).map(_.path).filter(exists),
      prependShellScript = prependShellScript()
    )
  }

  def classpath = T{ Seq(resources(), compile().classes) }

  def jar = T{
    createJar(Seq(resources(), compile().classes).map(_.path).filter(exists), mainClass())
  }

  def run() = T.command{
    val main = mainClass().getOrElse(throw new RuntimeException("No mainClass provided!"))
    subprocess(main, runDepClasspath().map(_.path) :+ compile().classes.path)
  }

  def runMain(mainClass: String) = T.command{
    subprocess(mainClass, runDepClasspath().map(_.path) :+ compile().classes.path)
  }

  def console() = T.command{
    subprocess(
      mainClass = "scala.tools.nsc.MainGenericRunner",
      classPath = externalCompileDepClasspath().map(_.path) :+ compile().classes.path,
      options = Seq("-usejavacp")
    )
  }
}
