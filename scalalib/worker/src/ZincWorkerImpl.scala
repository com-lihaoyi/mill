package mill.scalalib.worker

import java.io.File
import java.util.Optional

import mill.api.Loose.Agg
import mill.api.{KeyedLockedCache, PathRef}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.api.Util.{grepJar, isDotty, scalaBinaryVersion}
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import mill.scalalib.api.{CompilationResult, ZincWorkerApi}
case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: File): DefinesClass =
    Locate.definesClass(classpathEntry)
}

class ZincWorkerImpl(compilerBridge: Either[
                       (ZincWorkerApi.Ctx, Array[os.Path], (String, String) => os.Path),
                       String => os.Path
                     ],
                     libraryJarNameGrep: (Agg[os.Path], String) => os.Path,
                     compilerJarNameGrep: (Agg[os.Path], String) => os.Path,
                     compilerCache: KeyedLockedCache[Compilers])
    extends ZincWorkerApi{
  private val ic = new sbt.internal.inc.IncrementalCompilerImpl()
  lazy val javaOnlyCompilers = {
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

  def docJar(scalaVersion: String,
             scalaOrganization: String,
             compilerClasspath: Agg[os.Path],
             scalacPluginClasspath: Agg[os.Path],
             args: Seq[String])
            (implicit ctx: ZincWorkerApi.Ctx): Boolean = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
    ) { compilers: Compilers =>
      val scaladocClass = compilers.scalac().scalaInstance().loader().loadClass("scala.tools.nsc.ScalaDoc")
      val scaladocMethod = scaladocClass.getMethod("process", classOf[Array[String]])
      scaladocMethod.invoke(scaladocClass.newInstance(), args.toArray).asInstanceOf[Boolean]
    }
  }
  /** Compile the bridge if it doesn't exist yet and return the output directory.
    *  TODO: Proper invalidation, see #389
    */
  def compileZincBridgeIfNeeded(scalaVersion: String, scalaOrganization: String, compilerJars: Array[File]): os.Path = {
    compilerBridge match{
      case Right(compiled) => compiled(scalaVersion)
      case Left((ctx0, compilerBridgeClasspath, srcJars)) =>
        val workingDir = ctx0.dest / scalaVersion
        val compiledDest = workingDir / 'compiled
        if (!os.exists(workingDir)) {
          ctx0.log.info("Compiling compiler interface...")

          os.makeDir.all(workingDir)
          os.makeDir.all(compiledDest)

          val sourceFolder = mill.api.IO.unpackZip(srcJars(scalaVersion, scalaOrganization))(workingDir)
          val classloader = mill.api.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)
          val compilerMain = classloader.loadClass(
            if (isDotty(scalaVersion)) "dotty.tools.dotc.Main"
            else "scala.tools.nsc.Main"
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

  }



  def discoverMainClasses(compilationResult: CompilationResult)
                         (implicit ctx: ZincWorkerApi.Ctx): Seq[String] = {
    def toScala[A](o: Optional[A]): Option[A] = if (o.isPresent) Some(o.get) else None

    toScala(FileAnalysisStore.binary(compilationResult.analysisFile.toIO).get())
      .map(_.getAnalysis)
      .flatMap{
        case analysis: Analysis =>
          Some(analysis.infos.allInfos.values.flatMap(_.getMainClasses).toSeq.sorted)
        case _ =>
          None
      }
      .getOrElse(Seq.empty[String])
  }

  def compileJava(upstreamCompileOutput: Seq[CompilationResult],
                  sources: Agg[os.Path],
                  compileClasspath: Agg[os.Path],
                  javacOptions: Seq[String])
                 (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] = {
    val (zincFile, classesDir) = compileJava0(
      upstreamCompileOutput,
      sources,
      compileClasspath,
      javacOptions
    )
    CompilationResult(zincFile, PathRef(classesDir))
  }
  def compileJava0(upstreamCompileOutput: Seq[CompilationResult],
                   sources: Agg[os.Path],
                   compileClasspath: Agg[os.Path],
                   javacOptions: Seq[String])
                  (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
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
                   scalaOrganization: String,
                   scalacOptions: Seq[String],
                   compilerClasspath: Agg[os.Path],
                   scalacPluginClasspath: Agg[os.Path])
                  (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] = {
    val (zincFile, classesDir) = compileMixed0(
      upstreamCompileOutput,
      sources,
      compileClasspath,
      javacOptions,
      scalaVersion,
      scalaOrganization,
      scalacOptions,
      compilerClasspath,
      scalacPluginClasspath
    )
    CompilationResult(zincFile, PathRef(classesDir))
  }

  def compileMixed0(upstreamCompileOutput: Seq[CompilationResult],
                    sources: Agg[os.Path],
                    compileClasspath: Agg[os.Path],
                    javacOptions: Seq[String],
                    scalaVersion: String,
                    scalaOrganization: String,
                    scalacOptions: Seq[String],
                    compilerClasspath: Agg[os.Path],
                    scalacPluginClasspath: Agg[os.Path])
                   (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
    ) {compilers: Compilers =>
      compileInternal(
        upstreamCompileOutput,
        sources,
        compileClasspath,
        javacOptions,
        scalacOptions = scalacPluginClasspath.map(jar => s"-Xplugin:$jar").toSeq ++ scalacOptions,
        compilers
      )
    }
  }

  private def withCompilers[T](scalaVersion: String,
                               scalaOrganization: String,
                               compilerClasspath: Agg[os.Path],
                               scalacPluginClasspath: Agg[os.Path])
                              (f: Compilers => T)
                              (implicit ctx: ZincWorkerApi.Ctx)= {
    val combinedCompilerClasspath = compilerClasspath ++ scalacPluginClasspath
    val combinedCompilerJars = combinedCompilerClasspath.toArray.map(_.toIO)

    val compiledCompilerBridge = compileZincBridgeIfNeeded(
      scalaVersion,
      scalaOrganization,
      compilerClasspath.toArray.map(_.toIO)
    )

    val compilerBridgeSig = os.mtime(compiledCompilerBridge)

    val compilersSig =
      compilerBridgeSig +
        combinedCompilerClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum

    compilerCache.withCachedValue(compilersSig){
      val compilerJar =
        if (isDotty(scalaVersion))
          grepJar(compilerClasspath, s"dotty-compiler_${scalaBinaryVersion(scalaVersion)}", scalaVersion)
        else
          compilerJarNameGrep(compilerClasspath, scalaVersion)
      val scalaInstance = new ScalaInstance(
        version = scalaVersion,
        loader = mill.api.ClassLoader.create(combinedCompilerJars.map(_.toURI.toURL), null),
        libraryJar = libraryJarNameGrep(compilerClasspath, scalaVersion).toIO,
        compilerJar = compilerJar.toIO,
        allJars = combinedCompilerJars,
        explicitActual = None
      )
      ic.compilers(
        scalaInstance,
        ClasspathOptionsUtil.boot,
        None,
        ZincUtil.scalaCompiler(scalaInstance, compiledCompilerBridge.toIO)
      )
    }(f)
  }

  private def compileInternal(upstreamCompileOutput: Seq[CompilationResult],
                              sources: Agg[os.Path],
                              compileClasspath: Agg[os.Path],
                              javacOptions: Seq[String],
                              scalacOptions: Seq[String],
                              compilers: Compilers)
                             (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
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

      mill.api.Result.Success((zincFile, classesDir))
    }catch{case e: CompileFailed => mill.api.Result.Failure(e.toString)}
  }
}
