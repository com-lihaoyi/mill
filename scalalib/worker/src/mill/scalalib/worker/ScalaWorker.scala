package mill.scalalib.worker

import java.io.File
import java.util.Optional

import ammonite.ops.{Path, exists, ls, mkdir}
import ammonite.util.Colors
import mill.Agg
import mill.eval.PathRef
import mill.scalalib.{CompilationResult, Lib, TestRunner}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.Lib.grepJar
import mill.util.{Ctx, PrintLogger}
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange

case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: File): DefinesClass =
    Locate.definesClass(classpathEntry)
}

object ScalaWorker{

  def main(args: Array[String]): Unit = {
    try{
      var i = 0
      def readArray() = {
        val count = args(i).toInt
        val slice = args.slice(i + 1, i + count + 1)
        i = i + count + 1
        slice
      }
      val frameworks = readArray()
      val classpath = readArray()
      val arguments = readArray()
      val outputPath = args(i + 0)
      val colored = args(i + 1)
      val testCp = args(i + 2)
      val homeStr = args(i + 3)
      val ctx = new Ctx.Log with Ctx.Home {
        val log = PrintLogger(
          colored == "true",
          if(colored == "true") Colors.Default
          else Colors.BlackWhite,
          System.out,
          System.err,
          System.err,
          System.in
        )
        val home = Path(homeStr)
      }
      val result = Lib.runTests(
        frameworkInstances = TestRunner.frameworks(frameworks),
        entireClasspath = Agg.from(classpath.map(Path(_))),
        testClassfilePath = Agg(Path(testCp)),
        args = arguments
      )(ctx)

      // Clear interrupted state in case some badly-behaved test suite
      // dirtied the thread-interrupted flag and forgot to clean up. Otherwise
      // that flag causes writing the results to disk to fail
      Thread.interrupted()
      ammonite.ops.write(Path(outputPath), upickle.default.write(result))
    }catch{case e: Throwable =>
      println(e)
      e.printStackTrace()
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }
}

class ScalaWorker(ctx0: mill.util.Ctx,
                  compilerBridgeClasspath: Array[String]) extends mill.scalalib.ScalaWorkerApi{
  @volatile var scalaClassloaderCache = Option.empty[(Long, ClassLoader)]
  @volatile var scalaInstanceCache = Option.empty[(Long, ScalaInstance)]

  def compileZincBridge(scalaVersion: String,
                        sourcesJar: Path,
                        compilerJars: Array[File]) = {
    val workingDir = ctx0.dest / scalaVersion
    val compiledDest = workingDir / 'compiled
    if (!exists(workingDir)) {

      println("Compiling compiler interface...")

      mkdir(workingDir)
      mkdir(compiledDest)

      val sourceFolder = mill.modules.Util.unpackZip(sourcesJar)(workingDir)
      val classloader = mill.util.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)
      val scalacMain = classloader.loadClass("scala.tools.nsc.Main")
      val argsArray = Array[String](
        "-d", compiledDest.toString,
        "-classpath", (compilerJars ++ compilerBridgeClasspath).mkString(File.pathSeparator)
      ) ++ ls.rec(sourceFolder.path).filter(_.ext == "scala").map(_.toString)

      scalacMain.getMethods
        .find(_.getName == "process")
        .get
        .invoke(null, argsArray)
    }

    compiledDest
  }



  def discoverMainClasses(compilationResult: CompilationResult)(implicit ctx: mill.util.Ctx): Seq[String] = {
    def toScala[A](o: Optional[A]): Option[A] = if (o.isPresent) Some(o.get) else None

    toScala(FileAnalysisStore.binary(compilationResult.analysisFile.toIO).get())
      .map(_.getAnalysis)
      .flatMap{
        case analysis: Analysis =>
          Some(analysis.infos.allInfos.values.map(_.getMainClasses).flatten.toSeq.sorted)
        case _ =>
          None
      }
      .getOrElse(Seq.empty[String])
  }


  def compileScala(scalaVersion: String,
                   sources: Agg[Path],
                   compilerBridgeSources: Path,
                   compileClasspath: Agg[Path],
                   compilerClasspath: Agg[Path],
                   scalacOptions: Seq[String],
                   scalacPluginClasspath: Agg[Path],
                   javacOptions: Seq[String],
                   upstreamCompileOutput: Seq[CompilationResult])
                  (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray
    val compilerJars = compilerClasspath.toArray.map(_.toIO)

    val compilerBridge = compileZincBridge(scalaVersion, compilerBridgeSources, compilerJars)

    val pluginJars = scalacPluginClasspath.toArray.map(_.toIO)

    val compilerClassloaderSig = compilerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    val scalaInstanceSig =
      compilerClassloaderSig + scalacPluginClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum

    val compilerClassLoader = scalaClassloaderCache match{
      case Some((k, v)) if k == compilerClassloaderSig => v
      case _ =>
        val classloader = mill.util.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)
        scalaClassloaderCache = Some((compilerClassloaderSig, classloader))
        classloader
    }

    val scalaInstance = scalaInstanceCache match{
      case Some((k, v)) if k == scalaInstanceSig => v
      case _ =>
        val scalaInstance = new ScalaInstance(
          version = scalaVersion,
          loader = mill.util.ClassLoader.create(pluginJars.map(_.toURI.toURL), compilerClassLoader),
          libraryJar = grepJar(compilerClasspath, s"scala-library-$scalaVersion.jar"),
          compilerJar = grepJar(compilerClasspath, s"scala-compiler-$scalaVersion.jar"),
          allJars = compilerJars ++ pluginJars,
          explicitActual = None
        )
        scalaInstanceCache = Some((scalaInstanceSig, scalaInstance))
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

    try {
      val newResult = ic.compile(
        ic.inputs(
          classpath = classesIODir +: compileClasspathFiles,
          sources = sources.toArray.map(_.toIO),
          classesDirectory = classesIODir,
          scalacOptions = (scalacPluginClasspath.map(jar => s"-Xplugin:${jar}") ++ scalacOptions).toArray,
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

      mill.eval.Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
    }catch{case e: CompileFailed => mill.eval.Result.Failure(e.toString)}
  }
}
