package mill
package scalalib

import java.io.File
import java.net.URLClassLoader
import java.util.Optional

import ammonite.ops._
import coursier.{
  Cache,
  Fetch,
  MavenRepository,
  Repository,
  Resolution,
  Module => CoursierModule
}
import mill.define.Worker
import mill.eval.{PathRef, Result}
import mill.util.{Ctx}
import mill.util.Loose.Agg
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] =
    upickle.default.macroRW
}

// analysisFile is represented by Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: Path, classes: PathRef)

object ZincWorker extends Worker[ZincWorker] {
  def make() = new ZincWorker
}
class ZincWorker {
  @volatile var scalaClassloaderCache = Option.empty[(Long, ClassLoader)]
  @volatile var scalaInstanceCache = Option.empty[(Long, ScalaInstance)]
}
object Lib {
  case class MockedLookup(am: File => Optional[CompileAnalysis])
      extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      am(classpathEntry)

    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  def grepJar(classPath: Agg[Path], s: String) = {
    classPath
      .find(_.toString.endsWith(s))
      .getOrElse(throw new Exception("Cannot find " + s))
      .toIO
  }

  def compileScala(
    zincWorker: ZincWorker,
    scalaVersion: String,
    sources: Agg[Path],
    compileClasspath: Agg[Path],
    compilerClasspath: Agg[Path],
    pluginClasspath: Agg[Path],
    compilerBridge: Path,
    scalacOptions: Seq[String],
    scalacPluginClasspath: Agg[Path],
    javacOptions: Seq[String],
    upstreamCompileOutput: Seq[CompilationResult]
  )(implicit ctx: Ctx): CompilationResult = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray

    val compilerJars = compilerClasspath.toArray.map(_.toIO)
    val pluginJars = pluginClasspath.toArray.map(_.toIO)

    val compilerClassloaderSig =
      compilerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    val scalaInstanceSig =
      compilerClassloaderSig + pluginClasspath
        .map(p => p.toString().hashCode + p.mtime.toMillis)
        .sum

    val compilerClassLoader = zincWorker.scalaClassloaderCache match {
      case Some((k, v)) if k == compilerClassloaderSig => v
      case _ =>
        val classloader =
          new URLClassLoader(compilerJars.map(_.toURI.toURL), null)
        zincWorker.scalaClassloaderCache = Some((compilerClassloaderSig, classloader))
        classloader
    }

    val scalaInstance = zincWorker.scalaInstanceCache match {
      case Some((k, v)) if k == scalaInstanceSig => v
      case _ =>
        val scalaInstance = new ScalaInstance(
          version = scalaVersion,
          loader = new URLClassLoader(pluginJars.map(_.toURI.toURL), compilerClassLoader),
          libraryJar = grepJar(compilerClasspath, s"scala-library-$scalaVersion.jar"),
          compilerJar = grepJar(compilerClasspath, s"scala-compiler-$scalaVersion.jar"),
          allJars = compilerJars ++ pluginJars,
          explicitActual = None
        )
        zincWorker.scalaInstanceCache = Some((scalaInstanceSig, scalaInstance))
        scalaInstance
    }

    mkdir(ctx.dest)

    val ic = new sbt.internal.inc.IncrementalCompilerImpl()

    val logger = {
      val consoleAppender =
        MainAppender.defaultScreen(ConsoleOut.printStreamOut(ctx.log.outputStream))
      val l = LogExchange.logger("Hello")
      LogExchange.unbindLoggerAppenders("Hello")
      LogExchange
        .bindLoggerAppenders("Hello", (consoleAppender -> sbt.util.Level.Info) :: Nil)
      l
    }

    def analysisMap(f: File): Optional[CompileAnalysis] = {
      if (f.isFile) {
        Optional.empty[CompileAnalysis]
      } else {
        upstreamCompileOutput
          .collectFirst {
            case CompilationResult(zincPath, classFiles)
                if classFiles.path.toNIO == f.toPath =>
              FileAnalysisStore
                .binary(zincPath.toIO)
                .get()
                .map[CompileAnalysis](_.getAnalysis)
          }
          .getOrElse(Optional.empty[CompileAnalysis])
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
        sources = for {
          root <- sources.toArray
          if exists(root)
          path <- ls.rec(root)
          if path.isFile && (path.ext == "scala" || path.ext == "java")
        } yield path.toIO,
        classesDirectory = classesIODir,
        scalacOptions = (scalacPluginClasspath
          .map(jar => s"-Xplugin:${jar}") ++ scalacOptions).toArray,
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

    store.set(AnalysisContents.create(newResult.analysis(), newResult.setup()))

    CompilationResult(zincFile, PathRef(classesDir))
  }

  def resolveDependencies(repositories: Seq[Repository],
                          scalaVersion: String,
                          scalaBinaryVersion: String,
                          deps: TraversableOnce[Dep],
                          sources: Boolean = false): Result[Agg[PathRef]] = {
    val flattened = deps.map {
      case Dep.Java(dep) => dep
      case Dep.Scala(dep) =>
        dep.copy(
          module = dep.module.copy(name = dep.module.name + "_" + scalaBinaryVersion)
        )
      case Dep.Point(dep) =>
        dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaVersion))
    }.toSet
    val start = Resolution(flattened)

    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val errs = resolution.metadataErrors
    if (errs.nonEmpty) {
      val header =
        s"""|
            |Resolution failed for ${errs.length} modules:
            |--------------------------------------------
            |""".stripMargin

      val errLines = errs
        .map {
          case ((module, vsn), errMsgs) =>
            s"  ${module.trim}:$vsn \n\t" + errMsgs.mkString("\n\t")
        }
        .mkString("\n")
      val msg = header + errLines + "\n"
      Result.Failure(msg)
    } else {
      val sourceOrJar =
        if (sources) resolution.classifiersArtifacts(Seq("sources"))
        else resolution.artifacts
      val localArtifacts: Seq[File] = scalaz.concurrent.Task
        .gatherUnordered(sourceOrJar.map(Cache.file(_).run))
        .unsafePerformSync
        .flatMap(_.toOption)

      Agg.from(
        localArtifacts
          .map(p => PathRef(Path(p), quick = true))
          .filter(_.path.ext == "jar")
      )
    }
  }
  def scalaCompilerIvyDeps(scalaVersion: String) =
    Agg[Dep](
      Dep.Java("org.scala-lang", "scala-compiler", scalaVersion),
      Dep.Java("org.scala-lang", "scala-reflect", scalaVersion)
    )
  def scalaRuntimeIvyDeps(scalaVersion: String) =
    Agg[Dep](Dep.Java("org.scala-lang", "scala-library", scalaVersion))
  def compilerBridgeIvyDep(scalaVersion: String) =
    Dep.Point(
      coursier.Dependency(
        coursier.Module("com.lihaoyi", "mill-bridge"),
        "0.1",
        transitive = false
      )
    )

  val DefaultShellScript: Seq[String] =
    Seq("#!/usr/bin/env sh", "exec java -jar \"$0\" \"$@\"")
}
