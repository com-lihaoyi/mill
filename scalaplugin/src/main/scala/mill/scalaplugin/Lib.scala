package mill
package scalaplugin

import java.io.File
import java.net.URLClassLoader
import java.util.Optional

import ammonite.ops._
import coursier.{Cache, Fetch, MavenRepository, Repository, Resolution}
import mill.define.Worker
import mill.eval.{PathRef, Result}
import mill.util.Ctx
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] = upickle.default.macroRW
}

// analysisFile is represented by Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: Path, classes: PathRef)

object ZincWorker extends Worker[ZincWorker]{
  def make() = new ZincWorker
}
class ZincWorker{
  var scalaInstanceCache = Option.empty[(Long, ScalaInstance)]
}
object Lib{
  case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      am(classpathEntry)

    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  def grepJar(classPath: Seq[Path], s: String) = {
    classPath
      .find(_.toString.endsWith(s))
      .getOrElse(throw new Exception("Cannot find " + s))
      .toIO
  }

  def compileScala(zincWorker: ZincWorker,
                   scalaVersion: String,
                   sources: Seq[Path],
                   compileClasspath: Seq[Path],
                   compilerClasspath: Seq[Path],
                   compilerBridge: Path,
                   scalacOptions: Seq[String],
                   scalacPluginClasspath: Seq[Path],
                   javacOptions: Seq[String],
                   upstreamCompileOutput: Seq[CompilationResult])
                  (implicit ctx: Ctx): CompilationResult = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray

    val compilerJars = compilerClasspath.toArray.map(_.toIO)

    val classloaderSig = compilerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum

    val scalaInstance = zincWorker.scalaInstanceCache match{
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
        zincWorker.scalaInstanceCache = Some((classloaderSig, scalaInstance))
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
        sources = sources.filter(_.toIO.exists()).flatMap(ls.rec).filter(x => x.isFile && x.ext == "scala").map(_.toIO).toArray,
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
          ZincUtil.scalaCompiler(scalaInstance, compilerBridge.toIO)
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
  def compilerBridgeIvyDep(scalaVersion: String) =
    Dep.Point(coursier.Dependency(coursier.Module("com.lihaoyi", "mill-bridge"), "0.1-SNAPSHOT", transitive = false))

  val DefaultShellScript: Seq[String] = Seq(
    "#!/usr/bin/env sh",
    "exec java -jar \"$0\" \"$@\""
  )
}