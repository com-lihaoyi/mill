package mill.javalib.zinc

import mill.api.JsonFormatters.*
import mill.api.PathRef
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.daemon.{Logger, Result}
import mill.client.lock.*
import mill.javalib.api.internal.*
import mill.javalib.api.{CompilationResult, JvmWorkerUtil, Versions}
import mill.javalib.internal.ZincCompilerBridgeProvider
import mill.javalib.internal.ZincCompilerBridgeProvider.AcquireResult
import mill.javalib.worker.*
import mill.javalib.zinc.ZincWorker.*
import mill.util.{CachedFactory, CachedFactoryWithInitData, RefCountedClassLoaderCache}
import sbt.internal.inc
import sbt.internal.inc.*
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.mill.SbtLoggerUtils
import xsbti.compile.*
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.{PathBasedFile, VirtualFile}

import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable

/** @param jobs number of parallel jobs */
class ZincWorker(jobs: Int) extends AutoCloseable { self =>
  private val incrementalCompiler = new sbt.internal.inc.IncrementalCompilerImpl()
  private val compilerBridgeLocks: mutable.Map[String, MemoryLock] = mutable.Map.empty

  private val classloaderCache = new RefCountedClassLoaderCache(
    sharedLoader = getClass.getClassLoader,
    sharedPrefixes = Seq("xsbti")
  ) {
    override def extraRelease(cl: ClassLoader): Unit = {
      for {
        cls <- {
          try Some(cl.loadClass("scala.tools.nsc.classpath.FileBasedCache$"))
          catch {
            case _: ClassNotFoundException => None
          }
        }
        moduleField <- {
          try Some(cls.getField("MODULE$"))
          catch {
            case _: NoSuchFieldException => None
          }
        }
        module = moduleField.get(null)
        timerField <- {
          try Some(cls.getDeclaredField("scala$tools$nsc$classpath$FileBasedCache$$timer"))
          catch {
            case _: NoSuchFieldException => None
          }
        }
        _ = timerField.setAccessible(true)
        timerOpt0 = timerField.get(module)
        getOrElseMethod <- timerOpt0.getClass.getMethods.find(_.getName == "getOrElse")
        timer <-
          Option(getOrElseMethod.invoke(timerOpt0, null).asInstanceOf[java.util.Timer])
      } {

        timer.cancel()
      }

    }
  }

  private val scalaCompilerCache =
    new CachedFactoryWithInitData[
      ScalaCompilerCacheKey,
      ZincCompilerBridgeProvider,
      ScalaCompilerCached
    ] {
      override def maxCacheSize: Int = jobs

      override def setup(
          key: ScalaCompilerCacheKey,
          compilerBridge: ZincCompilerBridgeProvider
      ): ScalaCompilerCached = {
        import key.*

        val combinedCompilerJars = combinedCompilerClasspath.iterator.map(_.path.toIO).toArray

        val compiledCompilerBridge = compileBridgeIfNeeded(
          scalaVersion,
          scalaOrganization,
          compilerClasspath.map(_.path),
          compilerBridge
        )
        val classLoader = classloaderCache.get(key.combinedCompilerClasspath)
        val scalaInstance = new inc.ScalaInstance(
          version = key.scalaVersion,
          loader = classLoader,
          loaderCompilerOnly = classLoader,
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
        val compilers = incrementalCompiler.compilers(
          javaTools = getLocalOrCreateJavaTools(),
          scalac = ZincUtil.scalaCompiler(scalaInstance, compiledCompilerBridge.toIO)
        )
        ScalaCompilerCached(classLoader, compilers)
      }

      override def teardown(key: ScalaCompilerCacheKey, value: ScalaCompilerCached): Unit = {
        classloaderCache.release(key.combinedCompilerClasspath)
      }
    }

  private val javaOnlyCompilerCache = new CachedFactory[JavaCompilerCacheKey, Compilers] {

    override def setup(key: JavaCompilerCacheKey): Compilers = {
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
        new inc.ScalaInstance(
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

      val javaTools = getLocalOrCreateJavaTools()
      val compilers = incrementalCompiler.compilers(javaTools, scalac)
      compilers
    }

    override def teardown(key: JavaCompilerCacheKey, value: Compilers): Unit = ()

    override def maxCacheSize: Int = jobs
  }

  def compileJava(
      op: ZincCompileJava,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      ctx: ZincWorker.InvocationContext,
      deps: ZincWorker.InvocationDependencies
  ): Result[CompilationResult] = {
    import op.*

    val cacheKey = JavaCompilerCacheKey(javacOptions)
    javaOnlyCompilerCache.withValue(cacheKey) { compilers =>
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
        auxiliaryClassFileExtensions = Seq.empty,
        ctx = ctx,
        deps = deps
      )
    }
  }

  def compileMixed(
      op: ZincCompileMixed,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      ctx: ZincWorker.InvocationContext,
      deps: ZincWorker.InvocationDependencies
  ): Result[CompilationResult] = {
    import op.*

    withScalaCompilers(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      javacOptions = javacOptions,
      deps.compilerBridge
    ) { compilers =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalacOptions = scalacOptions,
        compilers = compilers,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
        incrementalCompilation = incrementalCompilation,
        auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
        ctx = ctx,
        deps = deps
      )
    }
  }

  def scaladocJar(
      op: ZincScaladocJar,
      compilerBridge: ZincCompilerBridgeProvider
  ): Boolean = {
    import op.*

    withScalaCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
      JavaCompilerOptions.empty,
      compilerBridge
    ) { compilers =>
      // Not sure why dotty scaladoc is flaky, but add retries to workaround it
      // https://github.com/com-lihaoyi/mill/issues/4556
      mill.util.Retry(count = 2) {
        if (JvmWorkerUtil.isDotty(scalaVersion) || JvmWorkerUtil.isScala3Milestone(scalaVersion)) {
          // dotty 0.x and scala 3 milestones use the dotty-doc tool
          val dottydocClass =
            compilers.scalac().scalaInstance().loader().loadClass(
              "dotty.tools.dottydoc.DocDriver"
            )
          val dottydocMethod = dottydocClass.getMethod("process", classOf[Array[String]])
          val reporter =
            dottydocMethod.invoke(dottydocClass.getConstructor().newInstance(), args.toArray)
          val hasErrorsMethod = reporter.getClass.getMethod("hasErrors")
          !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
        } else if (JvmWorkerUtil.isScala3(scalaVersion)) {
          // DottyDoc makes use of `com.fasterxml.jackson.databind.Module` which
          // requires the ContextClassLoader to be set appropriately
          mill.api.ClassLoader.withContextClassLoader(this.getClass.getClassLoader) {
            val scaladocClass =
              compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.scaladoc.Main")

            val scaladocMethod = scaladocClass.getMethod("run", classOf[Array[String]])
            val reporter =
              scaladocMethod.invoke(scaladocClass.getConstructor().newInstance(), args.toArray)
            val hasErrorsMethod = reporter.getClass.getMethod("hasErrors")
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

  /** Constructs a [[ZincApi]] given the invocation context and dependencies. */
  def api(
      ctx: ZincWorker.InvocationContext,
      deps: ZincWorker.InvocationDependencies
  ): ZincApi = new ZincApi {

    override def apply(
        op: ZincOperation,
        reporter: Option[CompileProblemReporter],
        reportCachedProblems: Boolean
    ) = {

      self.apply(op, reporter, reportCachedProblems, ctx, deps)
    }
  }

  def close(): Unit = {
    scalaCompilerCache.close()
    javaOnlyCompilerCache.close()
    classloaderCache.close()
  }

  private def withScalaCompilers[T](
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javacOptions: JavaCompilerOptions,
      compilerBridge: ZincCompilerBridgeProvider
  )(f: Compilers => T) = {
    val cacheKey = ScalaCompilerCacheKey(
      scalaVersion,
      compilerClasspath,
      scalacPluginClasspath,
      scalaOrganization,
      javacOptions
    )
    scalaCompilerCache.withValue(cacheKey, compilerBridge) { cached =>
      f(cached.compilers)
    }
  }

  private def compileInternal(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javacOptions: JavaCompilerOptions,
      scalacOptions: Seq[String],
      compilers: Compilers,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String],
      zincCache: os.SubPath = os.sub / "zinc",
      ctx: ZincWorker.InvocationContext,
      deps: ZincWorker.InvocationDependencies
  ): Result[CompilationResult] = {

    os.makeDir.all(ctx.dest)

    val classesDir = ctx.dest / "classes"

    if (ctx.logDebugEnabled) {
      deps.log.debug(
        s"""Compiling:
           |  javacOptions: ${javacOptions.options.map("'" + _ + "'").mkString(" ")}
           |  scalacOptions: ${scalacOptions.map("'" + _ + "'").mkString(" ")}
           |  sources: ${sources.map("'" + _ + "'").mkString(" ")}
           |  classpath: ${compileClasspath.map("'" + _ + "'").mkString(" ")}
           |  output: $classesDir"""
          .stripMargin
      )
    }

    reporter.foreach(_.start())

    val consoleAppender = ConsoleAppender(
      name = "ZincLogAppender",
      deps.consoleOut,
      ansiCodesSupported = ctx.logPromptColored,
      useFormat = ctx.logPromptColored,
      suppressedMessage = _ => None
    )
    val loggerId = Thread.currentThread().getId.toString
    val zincLogLevel = if (ctx.zincLogDebug) sbt.util.Level.Debug else sbt.util.Level.Info
    val logger = SbtLoggerUtils.createLogger(loggerId, consoleAppender, zincLogLevel)

    val maxErrors = reporter.map(_.maxErrors).getOrElse(CompileProblemReporter.defaultMaxErrors)

    def mkNewReporter(mapper: (xsbti.Position => xsbti.Position) | Null) = reporter match {
      case None =>
        new ManagedLoggedReporter(maxErrors, logger) with RecordingReporter
          with TransformingReporter(ctx.logPromptColored, mapper) {}
      case Some(forwarder) =>
        new ManagedLoggedReporter(maxErrors, logger)
          with ForwardingReporter(forwarder)
          with RecordingReporter
          with TransformingReporter(ctx.logPromptColored, mapper) {}
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
          reporter.notifyProgress(progress = current, total = total)
          true
        }
      }
    }

    val finalScalacOptions = {
      val addColorNever = !ctx.logPromptColored &&
        compilers.scalac().scalaInstance().version().startsWith("3.") &&
        !scalacOptions.exists(_.startsWith("-color:")) // might be too broad
      if (addColorNever)
        "-color:never" +: scalacOptions
      else
        scalacOptions
    }

    val (originalSourcesMap, posMapperOpt) = PositionMapper.create(virtualSources)
    val newReporter = mkNewReporter(posMapperOpt.orNull)

    val inputs = incrementalCompiler.inputs(
      classpath = classpath,
      sources = virtualSources,
      classesDirectory = classesDir.toNIO,
      earlyJarPath = None,
      scalacOptions = finalScalacOptions.toArray,
      javacOptions = javacOptions.options.toArray,
      maxErrors = maxErrors,
      sourcePositionMappers = Array(),
      order = CompileOrder.Mixed,
      compilers = compilers,
      setup = incrementalCompiler.setup(
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
      sys.props(scalaColorProp) = if (ctx.logPromptColored) "true" else "false"
      val newResult = incrementalCompiler.compile(
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
      Result.Success(CompilationResult(ctx.dest / zincCache, PathRef(classesDir)))
    } catch {
      case e: CompileFailed =>
        Result.Failure(e.toString)
    } finally {
      for (rep <- reporter) {
        for (f <- sources) {
          rep.fileVisited(f.toNIO)
          for (f0 <- originalSourcesMap.get(f))
            rep.fileVisited(f0.toNIO)
        }
        rep.finish()
      }
      previousScalaColor match {
        case null => sys.props.remove(scalaColorProp)
        case _ => sys.props(scalaColorProp) = previousScalaColor
      }
    }
  }

  /**
   * If needed, compile (for Scala 2) or download (for Dotty) the compiler bridge.
   *
   * @return a path to the directory containing the compiled classes, or to the downloaded jar file
   */
  private def compileBridgeIfNeeded(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[os.Path],
      compilerBridge: ZincCompilerBridgeProvider
  ): os.Path = {
    val workingDir = compilerBridge.workspace / s"zinc-${Versions.zinc}" / scalaVersion

    os.makeDir.all(compilerBridge.workspace / "compiler-bridge-locks")
    val memoryLock = synchronized(
      compilerBridgeLocks.getOrElseUpdate(scalaVersion, new MemoryLock)
    )
    val compiledDest = workingDir / "compiled"
    val doneFile = compiledDest / "DONE"
    // Use a double-lock here because we need mutex both between threads within this
    // process, as well as between different processes since sometimes we are initializing
    // the compiler bridge inside a separate `ZincWorkerMain` subprocess
    val doubleLock = new DoubleLock(
      memoryLock,
      new FileLock((compilerBridge.workspace / "compiler-bridge-locks" / scalaVersion).toString)
    )
    try {
      doubleLock.lock()
      if (os.exists(doneFile)) compiledDest
      else {
        val acquired =
          compilerBridge.acquire(scalaVersion = scalaVersion, scalaOrganization = scalaOrganization)

        acquired match {
          case AcquireResult.Compiled(bridgeJar) => bridgeJar
          case AcquireResult.NotCompiled(bridgeClasspath, bridgeSourcesJar) =>
            ZincCompilerBridgeProvider.compile(
              compilerBridge.logInfo,
              workingDir,
              compiledDest,
              scalaVersion,
              compilerClasspath,
              bridgeClasspath,
              bridgeSourcesJar
            )
            os.write(doneFile, "")
            compiledDest
        }
      }
    } finally doubleLock.close()
  }

  def apply(
      op: ZincOperation,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      ctx: ZincWorker.InvocationContext,
      deps: ZincWorker.InvocationDependencies
  ): op.Response = {
    op match {
      case msg: ZincCompileJava =>
        compileJava(
          op = msg,
          reporter = reporter,
          reportCachedProblems = reportCachedProblems,
          ctx,
          deps
        ).asInstanceOf[op.Response]
      case msg: ZincCompileMixed =>
        compileMixed(
          msg,
          reporter = reporter,
          reportCachedProblems = reportCachedProblems,
          ctx,
          deps
        ).asInstanceOf[op.Response]
      case msg: ZincScaladocJar =>
        scaladocJar(msg, deps.compilerBridge).asInstanceOf[op.Response]
      case msg: ZincDiscoverTests =>
        mill.javalib.testrunner.DiscoverTestsMain(msg).asInstanceOf[op.Response]
      case msg: ZincGetTestTasks =>
        mill.javalib.testrunner.GetTestTasksMain(msg).asInstanceOf[op.Response]
      case msg: ZincDiscoverJunit5Tests =>
        mill.javalib.testrunner.DiscoverJunit5TestsMain(msg).asInstanceOf[op.Response]
    }

  }
}
object ZincWorker {

  /**
   * Dependencies of the invocation.
   *
   * Can come either from the local [[ZincWorker]] running in [[JvmWorkerImpl]] or from a zinc worker running
   * in a different process.
   */
  case class InvocationDependencies(
      log: Logger.Actions,
      consoleOut: ConsoleOut,
      compilerBridge: ZincCompilerBridgeProvider
  )

  /** The invocation context, always comes from the Mill's process. */
  case class InvocationContext(
      env: Map[String, String],
      dest: os.Path,
      logDebugEnabled: Boolean,
      logPromptColored: Boolean,
      zincLogDebug: Boolean
  ) derives upickle.ReadWriter

  private case class ScalaCompilerCacheKey(
      scalaVersion: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      scalaOrganization: String,
      javacOptions: JavaCompilerOptions
  ) {
    val combinedCompilerClasspath: Seq[PathRef] = compilerClasspath ++ scalacPluginClasspath
  }

  private case class ScalaCompilerCached(classLoader: URLClassLoader, compilers: Compilers)

  private case class JavaCompilerCacheKey(javacOptions: JavaCompilerOptions)

  private def getLocalOrCreateJavaTools(): JavaTools = {
    val compiler = javac.JavaCompiler.local.getOrElse(javac.JavaCompiler.fork())
    val docs = javac.Javadoc.local.getOrElse(javac.Javadoc.fork())
    javac.JavaTools(compiler, docs)
  }

  private def libraryJarNameGrep(compilerClasspath: Seq[PathRef], scalaVersion: String): PathRef =
    JvmWorkerUtil.grepJar(compilerClasspath, "scala-library", scalaVersion, sources = false)

  private def fileAnalysisStore(path: os.Path): AnalysisStore =
    ConsistentFileAnalysisStore.binary(
      file = path.toIO,
      mappers = ReadWriteMappers.getEmptyMappers,
      reproducible = true,
      // No need to utilize more than 8 cores to serialize a small file
      parallelism = math.min(Runtime.getRuntime.availableProcessors(), 8)
    )
}
