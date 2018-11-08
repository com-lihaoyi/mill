package mill.scalalib.worker

import java.io.File
import java.util.Optional

import ammonite.util.Colors
import mill.Agg
import mill.eval.PathRef
import mill.scalalib.{CompilationResult, Lib, TestRunner}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.Dep.isDotty
import mill.scalalib.Lib.{grepJar, scalaBinaryVersion}
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

class ZincWorkerImpl(ctx0: mill.util.Ctx,
                     compilerBridgeClasspath: Array[String]) extends mill.scalalib.ZincWorkerApi{
  private val ic = new sbt.internal.inc.IncrementalCompilerImpl()
  val javaOnlyCompilers = {
    // Keep the classpath as written by the user
    val classpathOptions = ClasspathOptions.of(false, false, false, false, false)

    val dummyFile = new java.io.File("")
    // Zinc does not have an entry point for Java-only compilation, so we need
    // to make up a dummy ScalaCompiler instance.
    val scalac = ZincUtil.scalaCompiler(
      new ScalaInstance("", null, null, dummyFile, dummyFile, new Array(0), Some("")), null,
      classpathOptions // this is used for javac too
    )

    ic.compilers(
      instance = null,
      classpathOptions,
      None,
      scalac
    )
  }

  @volatile var mixedCompilersCache = Option.empty[(Long, Compilers)]

  def docJar(scalaVersion: String,
             compilerBridgeSources: os.Path,
             compilerClasspath: Agg[os.Path],
             scalacPluginClasspath: Agg[os.Path],
             args: Seq[String])
            (implicit ctx: mill.util.Ctx): Boolean = {
    val compilers: Compilers = prepareCompilers(
      scalaVersion,
      compilerBridgeSources,
      compilerClasspath,
      scalacPluginClasspath
    )
    val scaladocClass = compilers.scalac().scalaInstance().loader().loadClass("scala.tools.nsc.ScalaDoc")
    val scaladocMethod = scaladocClass.getMethod("process", classOf[Array[String]])
    scaladocMethod.invoke(scaladocClass.newInstance(), args.toArray).asInstanceOf[Boolean]
  }
  /** Compile the bridge if it doesn't exist yet and return the output directory.
   *  TODO: Proper invalidation, see #389
   */
  def compileZincBridgeIfNeeded(scalaVersion: String,
                                sourcesJar: os.Path,
                                compilerJars: Array[File]): os.Path = {
    val workingDir = ctx0.dest / scalaVersion
    val compiledDest = workingDir / 'compiled
    if (!os.exists(workingDir)) {

      ctx0.log.info("Compiling compiler interface...")

      os.makeDir.all(workingDir)
      os.makeDir.all(compiledDest)

      val sourceFolder = mill.modules.Util.unpackZip(sourcesJar)(workingDir)
      val classloader = mill.util.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)
      val compilerMain = classloader.loadClass(
        if (isDotty(scalaVersion))
          "dotty.tools.dotc.Main"
        else
          "scala.tools.nsc.Main"
      )
      val argsArray = Array[String](
        "-d", compiledDest.toString,
        "-classpath", (compilerJars ++ compilerBridgeClasspath).mkString(File.pathSeparator)
      ) ++ os.walk(sourceFolder.path).filter(_.ext == "scala").map(_.toString)

      compilerMain.getMethod("process", classOf[Array[String]])
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

  def compileJava(upstreamCompileOutput: Seq[CompilationResult],
                  sources: Agg[os.Path],
                  compileClasspath: Agg[os.Path],
                  javacOptions: Seq[String])
                 (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    compileInternal(
      upstreamCompileOutput,
      sources,
      compileClasspath,
      javacOptions,
      scalacOptions = Nil,
      javaOnlyCompilers
    )
  }

  def compileMixed(upstreamCompileOutput: Seq[CompilationResult],
                   sources: Agg[os.Path],
                   compileClasspath: Agg[os.Path],
                   javacOptions: Seq[String],
                   scalaVersion: String,
                   scalacOptions: Seq[String],
                   compilerBridgeSources: os.Path,
                   compilerClasspath: Agg[os.Path],
                   scalacPluginClasspath: Agg[os.Path])
                  (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compilers: Compilers = prepareCompilers(
      scalaVersion,
      compilerBridgeSources,
      compilerClasspath,
      scalacPluginClasspath
    )

    compileInternal(
      upstreamCompileOutput,
      sources,
      compileClasspath,
      javacOptions,
      scalacOptions = scalacPluginClasspath.map(jar => s"-Xplugin:${jar}").toSeq ++ scalacOptions,
      compilers
    )
  }

  private def prepareCompilers(scalaVersion: String,
                               compilerBridgeSources: os.Path,
                               compilerClasspath: Agg[os.Path],
                               scalacPluginClasspath: Agg[os.Path])
                              (implicit ctx: mill.util.Ctx)= {
    val combinedCompilerClasspath = compilerClasspath ++ scalacPluginClasspath
    val combinedCompilerJars = combinedCompilerClasspath.toArray.map(_.toIO)

    val compilerBridge = compileZincBridgeIfNeeded(
      scalaVersion,
      compilerBridgeSources,
      compilerClasspath.toArray.map(_.toIO)
    )
    val compilerBridgeSig = os.mtime(compilerBridge)

    val compilersSig =
      compilerBridgeSig +
        combinedCompilerClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum

    val compilers = mixedCompilersCache match {
      case Some((k, v)) if k == compilersSig => v
      case _ =>
        val compilerName =
          if (isDotty(scalaVersion))
            s"dotty-compiler_${scalaBinaryVersion(scalaVersion)}"
          else
            "scala-compiler"
        val scalaInstance = new ScalaInstance(
          version = scalaVersion,
          loader = mill.util.ClassLoader.create(combinedCompilerJars.map(_.toURI.toURL), null),
          libraryJar = grepJar(compilerClasspath, "scala-library", scalaVersion).toIO,
          compilerJar = grepJar(compilerClasspath, compilerName, scalaVersion).toIO,
          allJars = combinedCompilerJars,
          explicitActual = None
        )
        val compilers = ic.compilers(
          scalaInstance,
          ClasspathOptionsUtil.boot,
          None,
          ZincUtil.scalaCompiler(scalaInstance, compilerBridge.toIO)
        )
        mixedCompilersCache = Some((compilersSig, compilers))
        compilers
    }
    compilers
  }

  private def compileInternal(upstreamCompileOutput: Seq[CompilationResult],
                              sources: Agg[os.Path],
                              compileClasspath: Agg[os.Path],
                              javacOptions: Seq[String],
                              scalacOptions: Seq[String],
                              compilers: Compilers)
                             (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    os.makeDir.all(ctx.dest)

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

    val inputs = ic.inputs(
      classpath = classesIODir +: compileClasspath.map(_.toIO).toArray,
      sources = sources.toArray.map(_.toIO),
      classesDirectory = classesIODir,
      scalacOptions = scalacOptions.toArray,
      javacOptions = javacOptions.toArray,
      maxErrors = 10,
      sourcePositionMappers = Array(),
      order = CompileOrder.Mixed,
      compilers = compilers,
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
    )

    try {
      val newResult = ic.compile(
        in = inputs,
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
