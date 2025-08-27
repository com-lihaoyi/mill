package mill.scalalib.worker

import mill.api.Loose.Agg
import mill.api.{CachedFactory, CompileProblemReporter, Ctx, PathRef, Result, internal}
import mill.scalalib.api.{CompilationResult, JvmWorkerApi, JvmWorkerUtil, Versions}
import os.Path
import sbt.internal.inc.{
  Analysis,
  CompileFailed,
  FreshCompilerCache,
  ManagedLoggedReporter,
  MappedFileConverter,
  ScalaInstance,
  Stamps,
  ZincUtil,
  javac
}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.mill.SbtLoggerUtils
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.{
  AnalysisContents,
  AnalysisStore,
  AuxiliaryClassFileExtension,
  ClasspathOptions,
  CompileAnalysis,
  CompileOrder,
  Compilers,
  IncOptions,
  JavaTools,
  MiniSetup,
  PreviousResult
}
import xsbti.{PathBasedFile, VirtualFile}
import xsbti.compile.CompileProgress

import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable
import scala.util.Properties.isWin

@internal
class ZincWorkerImpl(
    compilerBridge: Either[
      (JvmWorkerApi.Ctx, (String, String) => (Option[Agg[PathRef]], PathRef)),
      String => PathRef
    ],
    jobs: Int,
    compileToJar: Boolean,
    zincLogDebug: Boolean,
    javaHome: Option[PathRef],
    close0: () => Unit
) extends JvmWorkerApi with AutoCloseable {
  val libraryJarNameGrep: (Agg[PathRef], String) => PathRef =
    JvmWorkerUtil.grepJar(_, "scala-library", _, sources = false)

  case class CompileCacheKey(
      scalaVersion: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      scalaOrganization: String,
      javacRuntimeOptions: Seq[String]
  ) {
    val combinedCompilerClasspath: Seq[PathRef] = compilerClasspath ++ scalacPluginClasspath
    val compilersSig: Int =
      combinedCompilerClasspath.hashCode() + scalaVersion.hashCode() +
        scalaOrganization.hashCode() + javacRuntimeOptions.hashCode()
  }
  object scalaCompilerCache extends CachedFactory[CompileCacheKey, (URLClassLoader, Compilers)] {

    override def maxCacheSize = jobs

    override def setup(key: CompileCacheKey): (URLClassLoader, Compilers) = {
      import key._

      val combinedCompilerJars = combinedCompilerClasspath.iterator.map(_.path.toIO).toArray

      val compiledCompilerBridge = compileBridgeIfNeeded(
        scalaVersion,
        scalaOrganization,
        compilerClasspath
      )
      val loader = getCachedClassLoader(compilersSig, combinedCompilerJars)
      val scalaInstance = new ScalaInstance(
        version = key.scalaVersion,
        loader = loader,
        loaderCompilerOnly = loader,
        loaderLibraryOnly = ClasspathUtil.rootLoader,
        libraryJars = Array(libraryJarNameGrep(
          compilerClasspath,
            // if Dotty or Scala 3.0 - 3.7, use the 2.13 version of the standard library
            if (JvmWorkerUtil.enforceScala213Library(key.scalaVersion)) "2.13."
            // otherwise use the library matching the Scala version
            else key.scalaVersion
        ).path.toIO),
        compilerJars = combinedCompilerJars,
        allJars = combinedCompilerJars,
        explicitActual = None
      )
      val compilers = ic.compilers(
        javaTools = getLocalOrCreateJavaTools(javacRuntimeOptions),
        scalac = ZincUtil.scalaCompiler(scalaInstance, compiledCompilerBridge.toIO)
      )
      (loader, compilers)
    }

    override def teardown(key: CompileCacheKey, value: (URLClassLoader, Compilers)): Unit = {
      import key._
      classloaderCache.updateWith(compilersSig) {
        case Some((cl, 1)) =>
          cl.close()
          None
        case Some((cl, n)) if n > 1 => Some((cl, n - 1))
        // No other cases; n should never be zero or negative
      }
    }
  }

  private[this] val classloaderCache =
    collection.mutable.LinkedHashMap.empty[Long, (URLClassLoader, Int)]

  def getCachedClassLoader(
      compilersSig: Long,
      combinedCompilerJars: Array[java.io.File]
  ): URLClassLoader = {
    classloaderCache.synchronized {
      classloaderCache.get(compilersSig) match {
        case Some((cl, i)) =>
          classloaderCache(compilersSig) = (cl, i + 1)
          cl
        case _ =>
          // the Scala compiler must load the `xsbti.*` classes from the same loader as `ZincWorkerImpl`
          val cl = mill.api.ClassLoader.create(
            combinedCompilerJars.map(_.toURI.toURL).toSeq,
            parent = null,
            sharedLoader = getClass.getClassLoader,
            sharedPrefixes = Seq("xsbti")
          )(new Ctx.Home { override def home: Path = os.home })
          classloaderCache.update(compilersSig, (cl, 1))
          cl
      }
    }
  }

  object javaOnlyCompilerCache extends CachedFactory[Seq[String], Compilers] {

    override def setup(key: Seq[String]): Compilers = {
      // Only options relevant for the compiler runtime influence the cached instance
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

      val javaTools = getLocalOrCreateJavaTools(key)

      val compilers = ic.compilers(javaTools, scalac)
      compilers

    }

    override def teardown(key: Seq[String], value: Compilers): Unit = ()

    override def maxCacheSize: Int = jobs
  }

  private def zincLogLevel = if (zincLogDebug) sbt.util.Level.Debug else sbt.util.Level.Info
  private[this] val ic = new sbt.internal.inc.IncrementalCompilerImpl()

  private def filterJavacRuntimeOptions(opt: String): Boolean = opt.startsWith("-J")

  private def getLocalOrCreateJavaTools(javacRuntimeOptions: Seq[String]): JavaTools = {
    val javaHome = this.javaHome.map(_.path.toNIO)
    val (javaCompiler, javaDoc) =
      // Local java compilers don't accept -J flags so when we put this together if we detect
      // any javacOptions starting with -J we ensure we have a non-local Java compiler which
      // can handle them.
      if (javacRuntimeOptions.exists(filterJavacRuntimeOptions) || javaHome.isDefined) {
        (javac.JavaCompiler.fork(javaHome), javac.Javadoc.fork(javaHome))

      } else {
        val compiler = javac.JavaCompiler.local.getOrElse(javac.JavaCompiler.fork(None))
        val docs = javac.Javadoc.local.getOrElse(javac.Javadoc.fork())
        (compiler, docs)
      }
    javac.JavaTools(javaCompiler, javaDoc)
  }

  val compilerBridgeLocks: mutable.Map[String, Object] =
    collection.mutable.Map.empty[String, Object]

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      args: Seq[String]
  )(implicit ctx: JvmWorkerApi.Ctx): Boolean = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
      Seq()
    ) { (compilers: Compilers) =>
      // Not sure why dotty scaladoc is flaky, but add retries to workaround it
      // https://github.com/com-lihaoyi/mill/issues/4556
      mill.api.Retry(count = 2) {
        if (JvmWorkerUtil.isDotty(scalaVersion) || JvmWorkerUtil.isScala3Milestone(scalaVersion)) {
          // dotty 0.x and scala 3 milestones use the dotty-doc tool
          val dottydocClass =
            compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.dottydoc.DocDriver")
          val dottydocMethod = dottydocClass.getMethod("process", classOf[Array[String]])
          val reporter =
            dottydocMethod.invoke(dottydocClass.getConstructor().newInstance(), args.toArray)
          val hasErrorsMethod = reporter.getClass().getMethod("hasErrors")
          !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
        } else if (JvmWorkerUtil.isScala3(scalaVersion)) {
          // DottyDoc makes use of `com.fasterxml.jackson.databind.Module` which
          // requires the ContextClassLoader to be set appropriately
          mill.api.ClassLoader.withContextClassLoader(getClass.getClassLoader) {

            val scaladocClass =
              compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.scaladoc.Main")

            val scaladocMethod = scaladocClass.getMethod("run", classOf[Array[String]])
            val reporter =
              scaladocMethod.invoke(scaladocClass.getConstructor().newInstance(), args.toArray)
            val hasErrorsMethod = reporter.getClass().getMethod("hasErrors")
            !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
          }
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
  }

  /** Compile the SBT/Zinc compiler bridge in the `compileDest` directory */
  def compileZincBridge(
      ctx0: JvmWorkerApi.Ctx,
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

    val sourceFolder = os.unzip(compilerBridgeSourcesJar, workingDir / "unpacked")
    val classloader = mill.api.ClassLoader.create(
      compilerClasspath.iterator.map(_.path.toIO.toURI.toURL).toSeq,
      null
    )(ctx0)

    val (sources, resources) =
      os.walk(sourceFolder).filter(os.isFile)
        .partition(a => a.ext == "scala" || a.ext == "java")

    resources.foreach { res =>
      val dest = compileDest / res.relativeTo(sourceFolder)
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
        if (JvmWorkerUtil.isDottyOrScala3(scalaVersion)) "dotty.tools.dotc.Main"
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

    toScala(fileAnalysisStore(compilationResult.analysisFile).get())
      .map(_.getAnalysis)
      .flatMap {
        case analysis: Analysis =>
          Some(analysis.infos.allInfos.values.flatMap(_.getMainClasses).toSeq.sorted)
        case _ =>
          None
      }
      .getOrElse(Seq.empty[String])
  }

  override def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    javaOnlyCompilerCache.withValue(javacOptions.filter(filterJavacRuntimeOptions)) { compilers =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalacOptions = Nil,
        compilers = compilers,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
        incrementalCompilation = incrementalCompilation,
        auxiliaryClassFileExtensions = Seq.empty[String]
      )
    }
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
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String]
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    withCompilers(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      javacOptions = javacOptions
    ) { (compilers: Compilers) =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalacOptions = scalacOptions,
        compilers = compilers,
        reporter = reporter,
        reportCachedProblems: Boolean,
        incrementalCompilation,
        auxiliaryClassFileExtensions
      )
    }
  }

  private def withCompilers[T](
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[PathRef],
      scalacPluginClasspath: Agg[PathRef],
      javacOptions: Seq[String]
  )(f: Compilers => T) = {

    val javacRuntimeOptions = javacOptions.filter(filterJavacRuntimeOptions)

    scalaCompilerCache.withValue(
      CompileCacheKey(
        scalaVersion,
        compilerClasspath.toSeq,
        scalacPluginClasspath.toSeq,
        scalaOrganization,
        javacRuntimeOptions
      )
    ) { case (loader, compilers) =>
      f(compilers)
    }
  }

  private def fileAnalysisStore(path: os.Path): AnalysisStore =
    ConsistentFileAnalysisStore.binary(
      file = path.toIO,
      mappers = ReadWriteMappers.getEmptyMappers(),
      reproducible = true,
      // No need to utilize more than 8 cores to serialize a small file
      parallelism = math.min(Runtime.getRuntime.availableProcessors(), 8)
    )

  private def compileInternal(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalacOptions: Seq[String],
      compilers: Compilers,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String],
      zincCache: os.SubPath = os.sub / "zinc"
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    os.makeDir.all(ctx.dest)

    val classesDir =
      if (compileToJar) ctx.dest / "classes.jar"
      else ctx.dest / "classes"

    if (ctx.log.debugEnabled) {
      ctx.log.debug(
        s"""Compiling:
           |  javacOptions: ${javacOptions.map("'" + _ + "'").mkString(" ")}
           |  scalacOptions: ${scalacOptions.map("'" + _ + "'").mkString(" ")}
           |  sources: ${sources.map("'" + _ + "'").mkString(" ")}
           |  classpath: ${compileClasspath.map("'" + _ + "'").mkString(" ")}
           |  output: ${classesDir}"""
          .stripMargin
      )
    }

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
        case Some(zincPath) => fileAnalysisStore(zincPath).get().map(_.getAnalysis)
        case None => Optional.empty[CompileAnalysis]
      }
    }

    val lookup = MockedLookup(analysisMap)

    val store = fileAnalysisStore(ctx.dest / zincCache)

    // Fix jdk classes marked as binary dependencies, see https://github.com/com-lihaoyi/mill/pull/1904
    val converter = MappedFileConverter.empty
    val classpath = (compileClasspath.iterator ++ Some(classesDir))
      .map(path => converter.toVirtualFile(path.toNIO))
      .toArray
    val virtualSources = sources.iterator
      .map(path => converter.toVirtualFile(path.toNIO))
      .toArray

    val incOptions = IncOptions.of().withAuxiliaryClassFiles(
      auxiliaryClassFileExtensions.map(new AuxiliaryClassFileExtension(_)).toArray
    )
    val compileProgress = reporter.map { reporter =>
      new CompileProgress {
        override def advance(
            current: Int,
            total: Int,
            prevPhase: String,
            nextPhase: String
        ): Boolean = {
          val percentage = current * 100 / total
          reporter.notifyProgress(percentage = percentage, total = total)
          true
        }
      }
    }

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
        cacheFile = zincCache.toNIO,
        cache = new FreshCompilerCache,
        incOptions = incOptions,
        reporter = newReporter,
        progress = compileProgress,
        earlyAnalysisStore = None,
        extra = Array()
      ),
      pr = if (incrementalCompilation) {
        val prev = store.get()
        PreviousResult.of(
          prev.map(_.getAnalysis): Optional[CompileAnalysis],
          prev.map(_.getMiniSetup): Optional[MiniSetup]
        )
      } else {
        PreviousResult.of(
          Optional.empty[CompileAnalysis],
          Optional.empty[MiniSetup]
        )
      },
      temporaryClassesDirectory = java.util.Optional.empty(),
      converter = converter,
      stampReader = Stamps.timeWrapBinaryStamps(converter)
    )

    val scalaColorProp = "scala.color"
    val previousScalaColor = sys.props(scalaColorProp)
    try {
      sys.props(scalaColorProp) = if (ctx.log.colored) "true" else "false"
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
      Result.Success(CompilationResult((ctx.dest / zincCache), PathRef(classesDir)))
    } catch {
      case e: CompileFailed =>
        Result.Failure(e.toString)
    } finally {
      reporter.foreach(r => sources.foreach(r.fileVisited(_)))
      reporter.foreach(_.finish())
      previousScalaColor match {
        case null => sys.props.remove(scalaColorProp)
        case v => sys.props(scalaColorProp) = previousScalaColor
      }
    }
  }

  override def close(): Unit = {
    val urlClassLoaders = classloaderCache
      .map(_._2._1)
      .collect { case v: AutoCloseable => v }

    urlClassLoaders.foreach(_.close())

    classloaderCache.clear()
    close0()
  }
}
