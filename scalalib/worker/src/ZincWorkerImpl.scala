package mill.scalalib.worker

import java.io.File
import java.util.Optional

import scala.ref.WeakReference
import mill.api.Loose.Agg
import mill.api.{KeyedLockedCache, PathRef}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.api.Util.{grepJar, isDotty, scalaBinaryVersion}
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import mill.scalalib.api.{CompilationResult, ZincWorkerApi}
import upickle.core.Visitor
case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: File): DefinesClass =
    Locate.definesClass(classpathEntry)
}

class ZincWorkerImpl(compilerBridge: Either[
                       (ZincWorkerApi.Ctx, (String, String) => (Option[Array[os.Path]], os.Path)),
                       String => os.Path
                     ],
                     libraryJarNameGrep: (Agg[os.Path], String) => os.Path,
                     compilerJarNameGrep: (Agg[os.Path], String) => os.Path,
                     compilerCache: KeyedLockedCache[Compilers],
                     compileToJar: Boolean)
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
      scalacPluginClasspath
    ) { compilers: Compilers =>
      val scaladocClass = compilers.scalac().scalaInstance().loader().loadClass("scala.tools.nsc.ScalaDoc")
      val scaladocMethod = scaladocClass.getMethod("process", classOf[Array[String]])
      scaladocMethod.invoke(scaladocClass.newInstance(), args.toArray).asInstanceOf[Boolean]
    }
  }

  /** Compile the SBT/Zinc compiler bridge in the `compileDest` directory */
  def compileZincBridge(ctx0: ZincWorkerApi.Ctx,
                        workingDir: os.Path,
                        compileDest: os.Path,
                        scalaVersion: String,
                        compilerJars: Array[File],
                        compilerBridgeClasspath: Array[os.Path],
                        compilerBridgeSourcesJar: os.Path): Unit = {
    ctx0.log.info("Compiling compiler interface...")

    os.makeDir.all(workingDir)
    os.makeDir.all(compileDest)

    val sourceFolder = mill.api.IO.unpackZip(compilerBridgeSourcesJar)(workingDir)
    val classloader = mill.api.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)

    val sources = os.walk(sourceFolder.path).filter(a => a.ext == "scala" || a.ext == "java")

    val argsArray = Array[String](
      "-d", compileDest.toString,
      "-classpath", (compilerJars ++ compilerBridgeClasspath).mkString(File.pathSeparator)
    ) ++ sources.map(_.toString)

    val allScala = sources.forall(_.ext == "scala")
    val allJava = sources.forall(_.ext == "java")
    if (allJava) {
      import scala.sys.process._
      (Seq("javac") ++ argsArray).!
    } else if (allScala) {
      val compilerMain = classloader.loadClass(
        if (isDotty(scalaVersion)) "dotty.tools.dotc.Main"
        else "scala.tools.nsc.Main"
      )
      compilerMain
        .getMethod("process", classOf[Array[String]])
        .invoke(null, argsArray)
    } else {
      throw new IllegalArgumentException("Currently not implemented case.")
    }
  }

  /** If needed, compile (for Scala 2) or download (for Dotty) the compiler bridge.
    * @return a path to the directory containing the compiled classes, or to the downloaded jar file
    */
  def compileBridgeIfNeeded(scalaVersion: String, scalaOrganization: String, compilerClasspath: Agg[os.Path]): os.Path = {
    compilerBridge match {
      case Right(compiled) => compiled(scalaVersion)
      case Left((ctx0, bridgeProvider)) =>
        val workingDir = ctx0.dest / scalaVersion
        val compiledDest = workingDir / 'compiled

        if (os.exists(compiledDest)) {
          compiledDest
        } else {
          val (cp, bridgeJar) = bridgeProvider(scalaVersion, scalaOrganization)
          cp match {
            case None =>
              bridgeJar
            case Some(bridgeClasspath) =>
              val compilerJars = compilerClasspath.toArray.map(_.toIO)
              compileZincBridge(ctx0, workingDir, compiledDest, scalaVersion, compilerJars, bridgeClasspath, bridgeJar)
              compiledDest
          }
        }
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
                  javacOptions: Seq[String],
                  reporter: Option[ManagedLoggedReporter])
                 (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] = {

    for(res <- compileJava0(
      upstreamCompileOutput.map(c => (c.analysisFile, c.classes.path)),
      sources,
      compileClasspath,
      javacOptions,
      reporter
    )) yield CompilationResult(res._1, PathRef(res._2))
  }
  def compileJava0(upstreamCompileOutput: Seq[(os.Path, os.Path)],
                   sources: Agg[os.Path],
                   compileClasspath: Agg[os.Path],
                   javacOptions: Seq[String],
                   reporter: Option[ManagedLoggedReporter])
                  (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
    compileInternal(
      upstreamCompileOutput,
      sources,
      compileClasspath,
      javacOptions,
      scalacOptions = Nil,
      javaOnlyCompilers,
      reporter
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
                   scalacPluginClasspath: Agg[os.Path],
                   reporter: Option[ManagedLoggedReporter])
                  (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] = {

    for (res <- compileMixed0(
      upstreamCompileOutput.map(c => (c.analysisFile, c.classes.path)),
      sources,
      compileClasspath,
      javacOptions,
      scalaVersion,
      scalaOrganization,
      scalacOptions,
      compilerClasspath,
      scalacPluginClasspath,
      reporter
    )) yield CompilationResult(res._1, PathRef(res._2))
  }

  def compileMixed0(upstreamCompileOutput: Seq[(os.Path, os.Path)],
                    sources: Agg[os.Path],
                    compileClasspath: Agg[os.Path],
                    javacOptions: Seq[String],
                    scalaVersion: String,
                    scalaOrganization: String,
                    scalacOptions: Seq[String],
                    compilerClasspath: Agg[os.Path],
                    scalacPluginClasspath: Agg[os.Path],
                    reporter: Option[ManagedLoggedReporter])
                   (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath
    ) {compilers: Compilers =>
      compileInternal(
        upstreamCompileOutput,
        sources,
        compileClasspath,
        javacOptions,
        scalacOptions = scalacPluginClasspath.map(jar => s"-Xplugin:$jar").toSeq ++ scalacOptions,
        compilers,
        reporter
      )
    }
  }

  // for now this just grows unbounded; YOLO
  // But at least we do not prevent unloading/garbage collecting of classloaders
  private[this] val classloaderCache = collection.mutable.LinkedHashMap.empty[Long, WeakReference[ClassLoader]]

  def getCachedClassLoader(compilersSig: Long,
                           combinedCompilerJars: Array[java.io.File])
                          (implicit ctx: ZincWorkerApi.Ctx) = {
    classloaderCache.synchronized {
      classloaderCache.get(compilersSig) match {
        case Some(WeakReference(cl)) => cl
        case _ =>
          val cl = mill.api.ClassLoader.create(combinedCompilerJars.map(_.toURI.toURL), null)
          classloaderCache.update(compilersSig, WeakReference(cl))
          cl
      }
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

    val compiledCompilerBridge = compileBridgeIfNeeded(
      scalaVersion,
      scalaOrganization,
      compilerClasspath
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
        loader = getCachedClassLoader(compilersSig, combinedCompilerJars),
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

  private def compileInternal(upstreamCompileOutput: Seq[(os.Path, os.Path)],
                              sources: Agg[os.Path],
                              compileClasspath: Agg[os.Path],
                              javacOptions: Seq[String],
                              scalacOptions: Seq[String],
                              compilers: Compilers,
                              reporter: Option[ManagedLoggedReporter])
                             (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
    os.makeDir.all(ctx.dest)

    val logger = {
      val consoleAppender = MainAppender.defaultScreen(ConsoleOut.printStreamOut(
        ctx.log.outputStream
      ))
      val id = Thread.currentThread().getId().toString
      val l = LogExchange.logger(id)
      LogExchange.unbindLoggerAppenders(id)
      LogExchange.bindLoggerAppenders(id, (consoleAppender -> sbt.util.Level.Info) :: Nil)
      l
    }
    val newReporter = reporter match {
      case None => new ManagedLoggedReporter(10, logger)
      case r: Option[ManagedLoggedReporter] => r.get
    }
    val analysisMap0 = upstreamCompileOutput.map(_.swap).toMap

    def analysisMap(f: File): Optional[CompileAnalysis] = {
      analysisMap0.get(os.Path(f)) match{
        case Some(zincPath) => FileAnalysisStore.binary(zincPath.toIO).get().map[CompileAnalysis](_.getAnalysis)
        case None => Optional.empty[CompileAnalysis]
      }
    }

    val lookup = MockedLookup(analysisMap)

    val zincFile = ctx.dest / 'zinc
    val classesDir =
      if (compileToJar) ctx.dest / "classes.jar"
      else ctx.dest / "classes"

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
        newReporter,
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
