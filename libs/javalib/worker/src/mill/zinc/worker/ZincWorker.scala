package mill.zinc.worker

import mill.api.PathRef
import mill.api.daemon.Result
import mill.api.daemon.internal.CompileProblemReporter
import mill.constants.CodeGenConstants
import mill.javalib.api.{CompilationResult, JvmWorkerApi, JvmWorkerUtil, Versions}
import mill.javalib.worker.{CompilersApi, JvmWorkerImpl, MockedLookup, RecordingReporter, ZincCompilerBridge}
import mill.util.{CachedFactory, RefCountedClassLoaderCache}
import mill.zinc.worker.ZincWorker.{JavaCompilerCacheKey, ScalaCompilerCacheKey, ScalaCompilerCached, fileAnalysisStore, getLocalOrCreateJavaTools, libraryJarNameGrep}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.inc.{CompileFailed, FreshCompilerCache, ManagedLoggedReporter, MappedFileConverter, ScalaInstance, Stamps, ZincUtil, javac}
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.mill.SbtLoggerUtils
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.{PathBasedFile, VirtualFile}
import xsbti.compile.{AnalysisContents, AnalysisStore, AuxiliaryClassFileExtension, ClasspathOptions, CompileAnalysis, CompileOrder, CompileProgress, Compilers, IncOptions, IncrementalCompiler, JavaTools, MiniSetup, PreviousResult}

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.Optional
import scala.collection.mutable
import scala.util.Properties.isWin


// TODO review: apply javaHome, javacRuntimeOptions
class ZincWorker(
  compilerBridge: ZincCompilerBridge,
  jobs: Int,
  compileToJar: Boolean,
  zincLogDebug: Boolean
) extends AutoCloseable, CompilersApi {
  private val incrementalCompiler = new sbt.internal.inc.IncrementalCompilerImpl()
  private val compilerBridgeLocks: mutable.Map[String, Object] = mutable.Map.empty[String, Object]
  private val zincLogLevel = if (zincLogDebug) sbt.util.Level.Debug else sbt.util.Level.Info

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

  object scalaCompilerCache extends CachedFactory[ScalaCompilerCacheKey, ScalaCompilerCached] {

    override def maxCacheSize: Int = jobs

    override def setup(key: ScalaCompilerCacheKey): ScalaCompilerCached = {
      import key._

      val combinedCompilerJars = combinedCompilerClasspath.iterator.map(_.path.toIO).toArray

      val compiledCompilerBridge = compileBridgeIfNeeded(
        scalaVersion,
        scalaOrganization,
        compilerClasspath
      )
      val classLoader = classloaderCache.get(key.combinedCompilerClasspath)
      val scalaInstance = new ScalaInstance(
        version = key.scalaVersion,
        loader = classLoader,
        loaderCompilerOnly = classLoader,
        loaderLibraryOnly = ClasspathUtil.rootLoader,
        libraryJars = Array(libraryJarNameGrep(
          compilerClasspath,
          // we don't support too outdated dotty versions
          // and because there will be no scala 2.14, so hardcode "2.13." here is acceptable
          if (JvmWorkerUtil.isDottyOrScala3(key.scalaVersion)) "2.13." else key.scalaVersion
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

  object javaOnlyCompilerCache extends CachedFactory[JavaCompilerCacheKey, Compilers] {

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

      val javaTools = getLocalOrCreateJavaTools()
      val compilers = incrementalCompiler.compilers(javaTools, scalac)
      compilers
    }

    override def teardown(key: JavaCompilerCacheKey, value: Compilers): Unit = ()

    override def maxCacheSize: Int = jobs
  }

  override def close(): Unit = {
    scalaCompilerCache.close()
    javaOnlyCompilerCache.close()
    classloaderCache.close()
  }

  private def compileInternal(
    upstreamCompileOutput: Seq[CompilationResult],
    sources: Seq[os.Path],
    compileClasspath: Seq[os.Path],
    javacOptions: Seq[String],
    scalacOptions: Seq[String],
    compilers: Compilers,
    reporter: Option[CompileProblemReporter],
    reportCachedProblems: Boolean,
    incrementalCompilation: Boolean,
    auxiliaryClassFileExtensions: Seq[String],
    zincCache: os.SubPath = os.sub / "zinc"
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    val requireReporter = ctx.env.get("MILL_JVM_WORKER_REQUIRE_REPORTER").contains("true")
    if (requireReporter && reporter.isEmpty)
      sys.error(
        """A reporter is required, but none was passed. The following allows to get
          |one from Mill tasks:
          |
          |    Task.reporter.apply(hashCode)
          |
          |`hashCode` should be the hashCode of the Mill module being compiled. Calling
          |this in a task defined in a module should work.
          |""".stripMargin
      )

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
           |  output: $classesDir"""
          .stripMargin
      )
    }

    reporter.foreach(_.start())

    val consoleAppender = ConsoleAppender(
      "ZincLogAppender",
      ConsoleOut.printStreamOut(ctx.log.streams.err),
      ctx.log.prompt.colored,
      ctx.log.prompt.colored,
      _ => None
    )
    val loggerId = Thread.currentThread().getId.toString
    val logger = SbtLoggerUtils.createLogger(loggerId, consoleAppender, zincLogLevel)

    val maxErrors = reporter.map(_.maxErrors).getOrElse(CompileProblemReporter.defaultMaxErrors)

    def mkNewReporter(mapper: (xsbti.Position => xsbti.Position) | Null) = reporter match {
      case None =>
        new ManagedLoggedReporter(maxErrors, logger) with RecordingReporter
          with TransformingReporter(ctx.log.prompt.colored, mapper) {}
      case Some(forwarder) =>
        new ManagedLoggedReporter(maxErrors, logger)
          with ForwardingReporter(forwarder)
          with RecordingReporter
          with TransformingReporter(ctx.log.prompt.colored, mapper) {}
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

    val finalScalacOptions = {
      val addColorNever = !ctx.log.prompt.colored &&
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
      javacOptions = javacOptions.toArray,
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
      sys.props(scalaColorProp) = if (ctx.log.prompt.colored) "true" else "false"
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
    compilerClasspath: Seq[PathRef]
  ): os.Path = {
    compilerBridge match {
      case ZincCompilerBridge.Compiled(forScalaVersion) => forScalaVersion(scalaVersion).path
      case ZincCompilerBridge.Provider(ctx0, bridgeProvider) =>
        val workingDir = ctx0.dest / s"zinc-${Versions.zinc}" / scalaVersion
        val lock = synchronized(compilerBridgeLocks.getOrElseUpdate(scalaVersion, new Object()))
        val compiledDest = workingDir / "compiled"
        lock.synchronized {
          if (os.exists(compiledDest / "DONE")) compiledDest
          else {
            val provided = bridgeProvider(scalaVersion = scalaVersion, scalaOrganization = scalaOrganization)
            provided.classpath match {
              case None => provided.bridgeJar.path
              case Some(bridgeClasspath) =>
                ZincCompilerBridge.compile(
                  ctx0,
                  workingDir,
                  compiledDest,
                  scalaVersion,
                  compilerClasspath,
                  bridgeClasspath,
                  provided.bridgeJar.path
                )
                os.write(compiledDest / "DONE", "")
                compiledDest
            }
          }
        }
    }
  }
}
object ZincWorker {
  /** Java compiler options, without the `-J` options (the Java runtime options). */
  case class JavaCompilerOptions private (options: Seq[String]) {
    {
      val runtimeOptions = options.filter(_.startsWith("-J"))
      if (runtimeOptions.nonEmpty) throw new IllegalArgumentException(
        s"Providing Java runtime options to javac is not supported."
      )
    }
  }
  object JavaCompilerOptions {
    def apply(options: Seq[String]): (runtime: JavaRuntimeOptions, compiler: JavaCompilerOptions) = {
      val prefix = "-J"
      val (runtimeOptions0, compilerOptions) = options.partition(_.startsWith(prefix))
      val runtimeOptions = JavaRuntimeOptions(runtimeOptions0.map(_.drop(prefix.length)))
      (runtimeOptions, new JavaCompilerOptions(compilerOptions))
    }
  }

  /** Options that are passed to the Java runtime. */
  case class JavaRuntimeOptions(options: Seq[String])

  case class ScalaCompilerCacheKey(
    scalaVersion: String,
    compilerClasspath: Seq[PathRef],
    scalacPluginClasspath: Seq[PathRef],
    scalaOrganization: String,
    javacOptions: JavaCompilerOptions
  ) {
    val combinedCompilerClasspath: Seq[PathRef] = compilerClasspath ++ scalacPluginClasspath

    val compilersSig: Int =
      scalaVersion.hashCode + combinedCompilerClasspath.hashCode + scalaOrganization.hashCode +
        javacOptions.hashCode
        // TODO review: `+ scalacPluginClasspath.hashCode`?
  }

  case class ScalaCompilerCached(classLoader: URLClassLoader, compilers: Compilers)

  case class JavaCompilerCacheKey(javacOptions: JavaCompilerOptions)


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
