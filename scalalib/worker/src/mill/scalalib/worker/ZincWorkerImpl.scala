package mill.scalalib.worker

import mill.api.Loose.Agg
import mill.api.{CompileProblemReporter, KeyedLockedCache, PathRef, Result, internal}
import mill.scalalib.api.{CompilationResult, ZincWorkerApi, ZincWorkerUtil, Versions}
import sbt.internal.inc.{
  Analysis,
  CompileFailed,
  FileAnalysisStore,
  FreshCompilerCache,
  ManagedLoggedReporter,
  MappedFileConverter,
  ScalaInstance,
  Stamps,
  ZincUtil,
  javac
}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.mill.SbtLoggerUtils
import xsbti.compile.{
  AnalysisContents,
  ClasspathOptions,
  CompileAnalysis,
  CompileOrder,
  CompileProgress,
  Compilers,
  IncOptions,
  JavaTools,
  MiniSetup,
  PreviousResult
}
import xsbti.{PathBasedFile, VirtualFile}

import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.ref.SoftReference
import scala.util.Properties.isWin

@internal
class ZincWorkerImpl(
    compilerBridge: Either[
      (ZincWorkerApi.Ctx, (String, String) => (Option[Agg[PathRef]], PathRef)),
      String => PathRef
    ],
    libraryJarNameGrep: (Agg[PathRef], String) => PathRef,
    compilerJarNameGrep: (Agg[PathRef], String) => PathRef,
    compilerCache: KeyedLockedCache[Compilers],
    compileToJar: Boolean,
    zincLogDebug: Boolean
) extends ZincWorkerApi with AutoCloseable {
  private val zincLogLevel = if (zincLogDebug) sbt.util.Level.Debug else sbt.util.Level.Info
  private[this] val ic = new sbt.internal.inc.IncrementalCompilerImpl()
  private val javaOnlyCompilersCache = mutable.Map.empty[Seq[String], SoftReference[Compilers]]

  private val filterJavacRuntimeOptions: String => Boolean = opt => opt.startsWith("-J")

  def javaOnlyCompilers(javacOptions: Seq[String]): Compilers = {
    // Only options relevant for the compiler runtime influence the cached instance
    val javacRuntimeOptions = javacOptions.filter(filterJavacRuntimeOptions)

    javaOnlyCompilersCache.get(javacRuntimeOptions) match {
      case Some(SoftReference(compilers)) => compilers
      case _ =>
        // Keep the classpath as written by the user
        val classpathOptions = ClasspathOptions.of(
          /*bootLibrary*/ false,
          /*compiler*/ false,
          /*extra*/ false,
          /*autoBoot*/ false,
          /*filterLibrary*/ false
        )

        val dummyFile = new java.io.File("")
        // Zinc does not have an entry point for Java-only compilation, so we need
        // to make up a dummy ScalaCompiler instance.
        val scalac = ZincUtil.scalaCompiler(
          new ScalaInstance(
            version = "",
            loader = null,
            loaderCompilerOnly = null,
            loaderLibraryOnly = null,
            libraryJars = Array(dummyFile),
            compilerJars = Array(dummyFile),
            allJars = new Array(0),
            explicitActual = Some("")
          ),
          dummyFile,
          classpathOptions // this is used for javac too
        )

        val javaTools = getLocalOrCreateJavaTools(javacRuntimeOptions)

        val compilers = ic.compilers(javaTools, scalac)
        javaOnlyCompilersCache.update(javacRuntimeOptions, SoftReference(compilers))
        compilers
    }
  }

  private def getLocalOrCreateJavaTools(javacRuntimeOptions: Seq[String]): JavaTools = {
    val (javaCompiler, javaDoc) =
      // Local java compilers don't accept -J flags so when we put this together if we detect
      // any javacOptions starting with -J we ensure we have a non-local Java compiler which
      // can handle them.
      if (javacRuntimeOptions.exists(filterJavacRuntimeOptions)) {
        (javac.JavaCompiler.fork(None), javac.Javadoc.fork(None))

      } else {
        val compiler = javac.JavaCompiler.local.getOrElse(javac.JavaCompiler.fork(None))
        val docs = javac.Javadoc.local.getOrElse(javac.Javadoc.fork())
        (compiler, docs)
      }
    javac.JavaTools(javaCompiler, javaDoc)
  }

  val compilerBridgeLocks = collection.mutable.Map.empty[String, Object]

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      args: Seq[String]
  )(implicit ctx: ZincWorkerApi.Ctx): Boolean = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
      Seq()
    ) { compilers: Compilers =>
      if (ZincWorkerUtil.isDotty(scalaVersion) || ZincWorkerUtil.isScala3Milestone(scalaVersion)) {
        // dotty 0.x and scala 3 milestones use the dotty-doc tool
        val dottydocClass =
          compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.dottydoc.DocDriver")
        val dottydocMethod = dottydocClass.getMethod("process", classOf[Array[String]])
        val reporter =
          dottydocMethod.invoke(dottydocClass.getConstructor().newInstance(), args.toArray)
        val hasErrorsMethod = reporter.getClass().getMethod("hasErrors")
        !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
      } else if (ZincWorkerUtil.isScala3(scalaVersion)) {
        val scaladocClass =
          compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.scaladoc.Main")
        val scaladocMethod = scaladocClass.getMethod("run", classOf[Array[String]])
        val reporter =
          scaladocMethod.invoke(scaladocClass.getConstructor().newInstance(), args.toArray)
        val hasErrorsMethod = reporter.getClass().getMethod("hasErrors")
        !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
      } else {
        val scaladocClass =
          compilers.scalac().scalaInstance().loader().loadClass("scala.tools.nsc.ScalaDoc")
        val scaladocMethod = scaladocClass.getMethod("process", classOf[Array[String]])
        scaladocMethod.invoke(
          scaladocClass.getConstructor().newInstance(),
          args.toArray
        ).asInstanceOf[Boolean]
      }
    }
  }

  /** Compile the SBT/Zinc compiler bridge in the `compileDest` directory */
  def compileZincBridge(
      ctx0: ZincWorkerApi.Ctx,
      workingDir: os.Path,
      compileDest: os.Path,
      scalaVersion: String,
      compilerClasspath: Agg[PathRef],
      compilerBridgeClasspath: Agg[PathRef],
      compilerBridgeSourcesJar: os.Path
  ): Unit = {
    if (scalaVersion == "2.12.0") {
      // The Scala 2.10.0 compiler fails on compiling the compiler bridge
      throw new IllegalArgumentException(
        "The current version of Zinc is incompatible with Scala 2.12.0.\n" +
          "Use Scala 2.12.1 or greater (2.12.12 is recommended)."
      )
    }

    ctx0.log.info("Compiling compiler interface...")

    os.makeDir.all(workingDir)
    os.makeDir.all(compileDest)

    val sourceFolder = mill.api.IO.unpackZip(compilerBridgeSourcesJar)(workingDir)
    val classloader = mill.api.ClassLoader.create(
      compilerClasspath.iterator.map(_.path.toIO.toURI.toURL).toSeq,
      null
    )(ctx0)

    val (sources, resources) =
      os.walk(sourceFolder.path).filter(os.isFile)
        .partition(a => a.ext == "scala" || a.ext == "java")

    resources.foreach { res =>
      val dest = compileDest / res.relativeTo(sourceFolder.path)
      os.move(res, dest, replaceExisting = true, createFolders = true)
    }

    val argsArray = Array[String](
      "-d",
      compileDest.toString,
      "-classpath",
      (compilerClasspath.iterator ++ compilerBridgeClasspath).map(_.path).mkString(
        File.pathSeparator
      )
    ) ++ sources.map(_.toString)

    val allScala = sources.forall(_.ext == "scala")
    val allJava = sources.forall(_.ext == "java")
    if (allJava) {
      val javacExe: String =
        sys.props
          .get("java.home")
          .map(h =>
            if (isWin) new File(h, "bin\\javac.exe")
            else new File(h, "bin/javac")
          )
          .filter(f => f.exists())
          .fold("javac")(_.getAbsolutePath())
      import scala.sys.process._
      (Seq(javacExe) ++ argsArray).!
    } else if (allScala) {
      val compilerMain = classloader.loadClass(
        if (ZincWorkerUtil.isDottyOrScala3(scalaVersion)) "dotty.tools.dotc.Main"
        else "scala.tools.nsc.Main"
      )
      compilerMain
        .getMethod("process", classOf[Array[String]])
        .invoke(null, argsArray ++ Array("-nowarn"))
    } else {
      throw new IllegalArgumentException("Currently not implemented case.")
    }
  }

  /**
   * If needed, compile (for Scala 2) or download (for Dotty) the compiler bridge.
   * @return a path to the directory containing the compiled classes, or to the downloaded jar file
   */
  def compileBridgeIfNeeded(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[PathRef]
  ): os.Path = {
    compilerBridge match {
      case Right(compiled) => compiled(scalaVersion).path
      case Left((ctx0, bridgeProvider)) =>
        val workingDir = ctx0.dest / s"zinc-${Versions.zinc}" / scalaVersion
        val lock = synchronized(compilerBridgeLocks.getOrElseUpdate(scalaVersion, new Object()))
        val compiledDest = workingDir / "compiled"
        lock.synchronized {
          if (os.exists(compiledDest / "DONE")) compiledDest
          else {
            val (cp, bridgeJar) = bridgeProvider(scalaVersion, scalaOrganization)
            cp match {
              case None => bridgeJar.path
              case Some(bridgeClasspath) =>
                compileZincBridge(
                  ctx0,
                  workingDir,
                  compiledDest,
                  scalaVersion,
                  compilerClasspath,
                  bridgeClasspath,
                  bridgeJar.path
                )
                os.write(compiledDest / "DONE", "")
                compiledDest
            }
          }

        }
    }

  }

  def discoverMainClasses(compilationResult: CompilationResult): Seq[String] = {
    def toScala[A](o: Optional[A]): Option[A] = if (o.isPresent) Some(o.get) else None

    toScala(FileAnalysisStore.binary(compilationResult.analysisFile.toIO).get())
      .map(_.getAnalysis)
      .flatMap {
        case analysis: Analysis =>
          Some(analysis.infos.allInfos.values.flatMap(_.getMainClasses).toSeq.sorted)
        case _ =>
          None
      }
      .getOrElse(Seq.empty[String])
  }

  def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): Result[CompilationResult] = {
    compileInternal(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javacOptions = javacOptions,
      scalacOptions = Nil,
      compilers = javaOnlyCompilers(javacOptions),
      reporter = reporter,
      reportCachedProblems = reportCachedProblems
    )
  }

  override def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): Result[CompilationResult] = {
    withCompilers(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      javacOptions = javacOptions
    ) { compilers: Compilers =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalacOptions = scalacOptions,
        compilers = compilers,
        reporter = reporter,
        reportCachedProblems: Boolean
      )
    }
  }

  // for now this just grows unbounded; YOLO
  // But at least we do not prevent unloading/garbage collecting of classloaders
  private[this] val classloaderCache =
    collection.mutable.LinkedHashMap.empty[Long, SoftReference[ClassLoader]]

  def getCachedClassLoader(compilersSig: Long, combinedCompilerJars: Array[java.io.File])(implicit
      ctx: ZincWorkerApi.Ctx
  ) = {
    classloaderCache.synchronized {
      classloaderCache.get(compilersSig) match {
        case Some(SoftReference(cl)) => cl
        case _ =>
          // the Scala compiler must load the `xsbti.*` classes from the same loader than `ZincWorkerImpl`
          val sharedPrefixes = Seq("xsbti")
          val cl = mill.api.ClassLoader.create(
            combinedCompilerJars.map(_.toURI.toURL).toSeq,
            parent = null,
            sharedLoader = getClass.getClassLoader,
            sharedPrefixes
          )
          classloaderCache.update(compilersSig, SoftReference(cl))
          cl
      }
    }
  }

  private def withCompilers[T](
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      javacOptions: Seq[String]
  )(f: Compilers => T)(implicit ctx: ZincWorkerApi.Ctx) = {
    val combinedCompilerClasspath = compilerClasspath ++ scalacPluginClasspath
    val javacRuntimeOptions = javacOptions.filter(filterJavacRuntimeOptions)
    val compilersSig =
      combinedCompilerClasspath.hashCode() + scalaVersion.hashCode() + scalaOrganization.hashCode() + javacRuntimeOptions.hashCode()
    val combinedCompilerJars = combinedCompilerClasspath.iterator.map(_.path.toIO).toArray

    val compiledCompilerBridge = compileBridgeIfNeeded(
      scalaVersion,
      scalaOrganization,
      compilerClasspath
    )

    compilerCache.withCachedValue(compilersSig) {
      val loader = getCachedClassLoader(compilersSig, combinedCompilerJars)
      val scalaInstance = new ScalaInstance(
        version = scalaVersion,
        loader = loader,
        loaderCompilerOnly = loader,
        loaderLibraryOnly = ClasspathUtil.rootLoader,
        libraryJars = Array(libraryJarNameGrep(
          compilerClasspath,
          // we don't support too outdated dotty versions
          // and because there will be no scala 2.14, so hardcode "2.13." here is acceptable
          if (ZincWorkerUtil.isDottyOrScala3(scalaVersion)) "2.13." else scalaVersion
        ).path.toIO),
        compilerJars = combinedCompilerJars,
        allJars = combinedCompilerJars,
        explicitActual = None
      )
      ic.compilers(
        javaTools = getLocalOrCreateJavaTools(javacRuntimeOptions),
        scalac = ZincUtil.scalaCompiler(scalaInstance, compiledCompilerBridge.toIO)
      )
    }(f)
  }

  private def compileInternal(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalacOptions: Seq[String],
      compilers: Compilers,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): Result[CompilationResult] = {
    os.makeDir.all(ctx.dest)

    reporter.foreach(_.start())

    val consoleAppender = ConsoleAppender(
      "ZincLogAppender",
      ConsoleOut.printStreamOut(ctx.log.errorStream),
      ctx.log.colored,
      ctx.log.colored,
      _ => None
    )
    val loggerId = Thread.currentThread().getId.toString
    val logger = SbtLoggerUtils.createLogger(loggerId, consoleAppender, zincLogLevel)

    val newReporter = reporter match {
      case None => new ManagedLoggedReporter(10, logger) with RecordingReporter
      case Some(forwarder) =>
        new ManagedLoggedReporter(10, logger) with RecordingReporter {

          override def logError(problem: xsbti.Problem): Unit = {
            forwarder.logError(new ZincProblem(problem))
            super.logError(problem)
          }

          override def logWarning(problem: xsbti.Problem): Unit = {
            forwarder.logWarning(new ZincProblem(problem))
            super.logWarning(problem)
          }

          override def logInfo(problem: xsbti.Problem): Unit = {
            forwarder.logInfo(new ZincProblem(problem))
            super.logInfo(problem)
          }

          override def printSummary(): Unit = {
            forwarder.printSummary()
            super.printSummary()
          }
        }
    }
    val analysisMap0 = upstreamCompileOutput.map(c => c.classes.path -> c.analysisFile).toMap

    def analysisMap(f: VirtualFile): Optional[CompileAnalysis] = {
      val analysisFile = f match {
        case pathBased: PathBasedFile => analysisMap0.get(os.Path(pathBased.toPath))
        case _ => None
      }
      analysisFile match {
        case Some(zincPath) => FileAnalysisStore.binary(zincPath.toIO).get().map(_.getAnalysis)
        case None => Optional.empty[CompileAnalysis]
      }
    }

    val lookup = MockedLookup(analysisMap)

    val zincFile = ctx.dest / "zinc"
    val classesDir =
      if (compileToJar) ctx.dest / "classes.jar"
      else ctx.dest / "classes"

    val store = FileAnalysisStore.binary(zincFile.toIO)

    // Fix jdk classes marked as binary dependencies, see https://github.com/com-lihaoyi/mill/pull/1904
    val converter = MappedFileConverter.empty
    val classpath = (compileClasspath.iterator ++ Some(classesDir))
      .map(path => converter.toVirtualFile(path.toNIO))
      .toArray
    val virtualSources = sources.iterator
      .map(path => converter.toVirtualFile(path.toNIO))
      .toArray

    val inputs = ic.inputs(
      classpath = classpath,
      sources = virtualSources,
      classesDirectory = classesDir.toNIO,
      earlyJarPath = None,
      scalacOptions = scalacOptions.toArray,
      javacOptions = javacOptions.toArray,
      maxErrors = 10,
      sourcePositionMappers = Array(),
      order = CompileOrder.Mixed,
      compilers = compilers,
      setup = ic.setup(
        lookup = lookup,
        skip = false,
        cacheFile = zincFile.toNIO,
        cache = new FreshCompilerCache,
        incOptions = IncOptions.of(),
        reporter = newReporter,
        progress = None,
        earlyAnalysisStore = None,
        extra = Array()
      ),
      pr = {
        val prev = store.get()
        PreviousResult.of(
          prev.map(_.getAnalysis): Optional[CompileAnalysis],
          prev.map(_.getMiniSetup): Optional[MiniSetup]
        )
      },
      temporaryClassesDirectory = java.util.Optional.empty(),
      converter = converter,
      stampReader = Stamps.timeWrapBinaryStamps(converter)
    )

    try {
      val newResult = ic.compile(
        in = inputs,
        logger = logger
      )

      if (reportCachedProblems) {
        newReporter.logOldProblems(newResult.analysis())
      }

      store.set(
        AnalysisContents.create(
          newResult.analysis(),
          newResult.setup()
        )
      )
      Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
    } catch {
      case e: CompileFailed =>
        Result.Failure(e.toString)
    } finally {
      reporter.foreach(r => sources.foreach(r.fileVisited(_)))
      reporter.foreach(_.finish())
    }
  }

  override def close(): Unit = {
    classloaderCache.clear()
    javaOnlyCompilersCache.clear()
  }
}
