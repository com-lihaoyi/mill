package mill.javalib.worker

import mill.util.CachedFactory
import mill.api._
import mill.api.daemon._
import mill.api.daemon.internal.internal
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.PathRef
import mill.constants.CodeGenConstants
import mill.javalib.api.{CompilationResult, Versions, JvmWorkerApi, JvmWorkerUtil}
import mill.util.RefCountedClassLoaderCache
import sbt.internal.inc.{
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

import java.nio.charset.StandardCharsets
import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Properties.isWin

//noinspection ScalaUnusedSymbol - used dynamically by classloading via a FQCN
@internal
class JvmWorkerImpl(
    compilerBridge: Either[
      (JvmWorkerApi.Ctx, (String, String) => (Option[Seq[PathRef]], PathRef)),
      String => PathRef
    ],
    jobs: Int,
    compileToJar: Boolean,
    zincLogDebug: Boolean,
    close0: () => Unit
) extends JvmWorkerApi with AutoCloseable {
  val libraryJarNameGrep: (Seq[PathRef], String) => PathRef =
    JvmWorkerUtil.grepJar(_, "scala-library", _, sources = false)

  case class ScalaCompileCacheKey(
      scalaVersion: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      scalaOrganization: String,
      javaHome: Option[os.Path],
      javacRuntimeOptions: Seq[String]
  ) {
    val combinedCompilerClasspath: Seq[PathRef] = compilerClasspath ++ scalacPluginClasspath
    val compilersSig: Int =
      combinedCompilerClasspath.hashCode() + scalaVersion.hashCode() +
        scalaOrganization.hashCode() + javacRuntimeOptions.hashCode()
  }

  case class CachedScalaCompiler(classLoader: URLClassLoader, compilers: Compilers)
  object scalaCompilerCache extends CachedFactory[ScalaCompileCacheKey, CachedScalaCompiler] {

    override def maxCacheSize: Int = jobs

    override def setup(key: ScalaCompileCacheKey): CachedScalaCompiler = {
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
        javaTools = getLocalOrCreateJavaTools(javaHome, javacRuntimeOptions),
        scalac = ZincUtil.scalaCompiler(scalaInstance, compiledCompilerBridge.toIO)
      )
      CachedScalaCompiler(classLoader, compilers)
    }

    override def teardown(key: ScalaCompileCacheKey, value: CachedScalaCompiler): Unit = {
      classloaderCache.release(key.combinedCompilerClasspath)
    }
  }

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

  case class JavaOnlyCompileCacheKey(javaHome: Option[os.Path], javacRuntimeOptions: Seq[String])
  object javaOnlyCompilerCache extends CachedFactory[JavaOnlyCompileCacheKey, Compilers] {

    override def setup(key: JavaOnlyCompileCacheKey): Compilers = {
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

      val javaTools = getLocalOrCreateJavaTools(key.javaHome, key.javacRuntimeOptions)

      val compilers = incrementalCompiler.compilers(javaTools, scalac)
      compilers
    }

    override def teardown(key: JavaOnlyCompileCacheKey, value: Compilers): Unit = ()

    override def maxCacheSize: Int = jobs
  }

  private def zincLogLevel = if (zincLogDebug) sbt.util.Level.Debug else sbt.util.Level.Info
  private val incrementalCompiler = new sbt.internal.inc.IncrementalCompilerImpl()

  private def filterJavacRuntimeOptions(opt: String): Boolean = opt.startsWith("-J")

  private def getLocalOrCreateJavaTools(
      javaHome0: Option[os.Path],
      javacRuntimeOptions: Seq[String]
  ): JavaTools = {
    val javaHome = javaHome0.map(_.toNIO)
    val (javaCompiler, javaDoc) =
      // Local java compilers don't accept -J flags so when we put this together if we detect
      // any javacOptions starting with -J we ensure we have a non-local Java compiler which
      // can handle them.
      if (javacRuntimeOptions.exists(filterJavacRuntimeOptions) || javaHome.isDefined) {
        (javac.JavaCompiler.fork(javaHome), javac.Javadoc.fork(javaHome))
      } else {
        val compiler = javac.JavaCompiler.local.getOrElse(javac.JavaCompiler.fork())
        val docs = javac.Javadoc.local.getOrElse(javac.Javadoc.fork())
        (compiler, docs)
      }
    javac.JavaTools(javaCompiler, javaDoc)
  }

  private val compilerBridgeLocks: mutable.Map[String, Object] =
    collection.mutable.Map.empty[String, Object]

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
      javaHome,
      Seq()
    ) { (compilers: Compilers) =>
      // Not sure why dotty scaladoc is flaky, but add retries to workaround it
      // https://github.com/com-lihaoyi/mill/issues/4556
      mill.util.Retry(count = 2) {
        if (JvmWorkerUtil.isDotty(scalaVersion) || JvmWorkerUtil.isScala3Milestone(scalaVersion)) {
          // dotty 0.x and scala 3 milestones use the dotty-doc tool
          val dottydocClass =
            compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.dottydoc.DocDriver")
          val dottydocMethod = dottydocClass.getMethod("process", classOf[Array[String]])
          val reporter =
            dottydocMethod.invoke(dottydocClass.getConstructor().newInstance(), args.toArray)
          val hasErrorsMethod = reporter.getClass.getMethod("hasErrors")
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

  /** Compile the `sbt`/Zinc compiler bridge in the `compileDest` directory */
  private def compileZincBridge(
      ctx0: JvmWorkerApi.Ctx,
      workingDir: os.Path,
      compileDest: os.Path,
      scalaVersion: String,
      compilerClasspath: Seq[PathRef],
      compilerBridgeClasspath: Seq[PathRef],
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
    val classloader = mill.util.Jvm.createClassLoader(
      compilerClasspath.map(_.path),
      parent = null
    )

    try {
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
    } finally classloader.close()
  }

  /**
   * If needed, compile (for Scala 2) or download (for Dotty) the compiler bridge.
   * @return a path to the directory containing the compiled classes, or to the downloaded jar file
   */
  private def compileBridgeIfNeeded(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef]
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

  override def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    javaOnlyCompilerCache.withValue(JavaOnlyCompileCacheKey(javaHome, javacOptions.filter(filterJavacRuntimeOptions))) {
      compilers =>
        compileInternal(
          upstreamCompileOutput = upstreamCompileOutput,
          sources = sources,
          compileClasspath = compileClasspath,
          javaHome: Option[os.Path],
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
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
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
      javaHome = javaHome,
      javacOptions = javacOptions
    ) { (compilers: Compilers) =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javaHome = javaHome,
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
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      javacOptions: Seq[String]
  )(f: Compilers => T) = {

    val javacRuntimeOptions = javacOptions.filter(filterJavacRuntimeOptions)

    scalaCompilerCache.withValue(
      ScalaCompileCacheKey(
        scalaVersion,
        compilerClasspath,
        scalacPluginClasspath,
        scalaOrganization,
        javaHome,
        javacRuntimeOptions
      )
    ) { cached =>
      f(cached.compilers)
    }
  }

  private def fileAnalysisStore(path: os.Path): AnalysisStore =
    ConsistentFileAnalysisStore.binary(
      file = path.toIO,
      mappers = ReadWriteMappers.getEmptyMappers,
      reproducible = true,
      // No need to utilize more than 8 cores to serialize a small file
      parallelism = math.min(Runtime.getRuntime.availableProcessors(), 8)
    )

  private def compileInternal(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      scalacOptions: Seq[String],
      compilers: Compilers,
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String],
      zincCache: os.SubPath = os.sub / "zinc"
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    import JvmWorkerImpl.{ForwardingReporter, TransformingReporter, PositionMapper}

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

  override def close(): Unit = {
    scalaCompilerCache.close()
    javaOnlyCompilerCache.close()

    close0()
    classloaderCache.close()
  }
}
object JvmWorkerImpl {
  private def intValue(oi: java.util.Optional[Integer], default: Int): Int = {
    if oi.isPresent then oi.get().intValue()
    else default
  }

  private object PositionMapper {
    import sbt.util.InterfaceUtil

    private val userCodeStartMarker = "//SOURCECODE_ORIGINAL_CODE_START_MARKER"

    /** Transforms positions of problems if coming from a build file. */
    private def lookup(buildSources: Map[String, xsbti.Position => xsbti.Position])(
        oldPos: xsbti.Position
    ): xsbti.Position = {
      val src = oldPos.sourcePath()
      if src.isPresent then {
        buildSources.get(src.get()) match {
          case Some(f) => f(oldPos)
          case _ => oldPos
        }
      } else {
        oldPos
      }
    }

    def create(sources: Array[VirtualFile])
        : (Map[os.Path, os.Path], Option[xsbti.Position => xsbti.Position]) = {
      val buildSources0 = {
        def isBuild(vf: VirtualFile) =
          CodeGenConstants.buildFileExtensions.asScala.exists(ex =>
            vf.id().endsWith(s".$ex")
          )

        sources.collect({
          case vf if isBuild(vf) =>
            val str = new String(vf.input().readAllBytes(), StandardCharsets.UTF_8)

            val lines = str.linesWithSeparators.toVector
            val adjustedFile = lines
              .collectFirst { case s"//SOURCECODE_ORIGINAL_FILE_PATH=$rest" => rest.trim }
              .getOrElse(sys.error(vf.id()))

            (vf.id(), adjustedFile, remap(lines, adjustedFile))
        })
      }

      val map = buildSources0
        .map {
          case (generated, original, _) =>
            os.Path(generated) -> os.Path(original)
        }
        .toMap
      val lookupOpt = Option.when(buildSources0.nonEmpty) {
        lookup(buildSources0.map { case (generated, _, f) => (generated, f) }.toMap)
      }
      (map, lookupOpt)
    }

    private def remap(
        lines: Vector[String],
        adjustedFile: String
    ): xsbti.Position => xsbti.Position = {
      val markerLine = lines.indexWhere(_.startsWith(userCodeStartMarker))

      val topWrapperLen = lines.take(markerLine + 1).map(_.length).sum

      val originPath = Some(adjustedFile)
      val originFile = Some(java.nio.file.Paths.get(adjustedFile).toFile)

      def userCode(offset: java.util.Optional[Integer]): Boolean =
        intValue(offset, -1) > topWrapperLen

      def inner(pos0: xsbti.Position): xsbti.Position = {
        if userCode(pos0.startOffset()) || userCode(pos0.offset()) then {
          val IArray(line, offset, startOffset, endOffset, startLine, endLine) =
            IArray(
              pos0.line(),
              pos0.offset(),
              pos0.startOffset(),
              pos0.endOffset(),
              pos0.startLine(),
              pos0.endLine()
            )
              .map(intValue(_, 1) - 1)

          val (baseLine, baseOffset) = (markerLine, topWrapperLen)

          InterfaceUtil.position(
            line0 = Some(line - baseLine),
            content = pos0.lineContent(),
            offset0 = Some(offset - baseOffset),
            pointer0 = InterfaceUtil.jo2o(pos0.pointer()),
            pointerSpace0 = InterfaceUtil.jo2o(pos0.pointerSpace()),
            sourcePath0 = originPath,
            sourceFile0 = originFile,
            startOffset0 = Some(startOffset - baseOffset),
            endOffset0 = Some(endOffset - baseOffset),
            startLine0 = Some(startLine - baseLine),
            startColumn0 = InterfaceUtil.jo2o(pos0.startColumn()),
            endLine0 = Some(endLine - baseLine),
            endColumn0 = InterfaceUtil.jo2o(pos0.endColumn())
          )
        } else {
          pos0
        }
      }

      inner
    }
  }

  private trait ForwardingReporter(forwarder: CompileProblemReporter)
      extends ManagedLoggedReporter {
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

  private trait TransformingReporter(
      color: Boolean,
      optPositionMapper: (xsbti.Position => xsbti.Position) | Null
  ) extends xsbti.Reporter {

    // Overriding this is necessary because for some reason the LoggedReporter doesn't transform positions
    // of Actions and DiagnosticRelatedInformation
    abstract override def log(problem0: xsbti.Problem): Unit = {
      val localMapper = optPositionMapper
      val problem = {
        if localMapper == null then problem0
        else TransformingReporter.transformProblem(color, problem0, localMapper)
      }
      super.log(problem)
    }
  }

  private object TransformingReporter {

    import scala.jdk.CollectionConverters.given
    import sbt.util.InterfaceUtil

    /** implements a transformation that returns the same object if the mapper has no effect. */
    private def transformProblem(
        color: Boolean,
        problem0: xsbti.Problem,
        mapper: xsbti.Position => xsbti.Position
    ): xsbti.Problem = {
      val pos0 = problem0.position()
      val related0 = problem0.diagnosticRelatedInformation()
      val actions0 = problem0.actions()
      val pos = mapper(pos0)
      val related = transformRelateds(related0, mapper)
      val actions = transformActions(actions0, mapper)
      val posIsNew = pos ne pos0
      if posIsNew || (related ne related0) || (actions ne actions0) then
        val rendered = {
          // if we transformed the position, then we must re-render the message
          if posIsNew then Some(dottyStyleMessage(color, problem0, pos))
          else InterfaceUtil.jo2o(problem0.rendered())
        }
        InterfaceUtil.problem(
          cat = problem0.category(),
          pos = pos,
          msg = problem0.message(),
          sev = problem0.severity(),
          rendered = rendered,
          diagnosticCode = InterfaceUtil.jo2o(problem0.diagnosticCode()),
          diagnosticRelatedInformation = anyToList(related),
          actions = anyToList(actions)
        )
      else
        problem0
    }

    private type JOrSList[T] = java.util.List[T] | List[T]

    private def anyToList[T](ts: JOrSList[T]): List[T] = ts match {
      case ts: List[T] => ts
      case ts: java.util.List[T] => ts.asScala.toList
    }

    /** Render the message in the style of dotty */
    private def dottyStyleMessage(
        color: Boolean,
        problem0: xsbti.Problem,
        pos: xsbti.Position
    ): String = {
      val base = problem0.message()
      val severity = problem0.severity()

      def shade(msg: String) =
        if color then
          severity match {
            case xsbti.Severity.Error => Console.RED + msg + Console.RESET
            case xsbti.Severity.Warn => Console.YELLOW + msg + Console.RESET
            case xsbti.Severity.Info => Console.BLUE + msg + Console.RESET
          }
        else msg

      val normCode = {
        problem0.diagnosticCode().filter(_.code() != "-1").map({ inner =>
          val prefix = s"[E${inner.code()}] "
          inner.explanation().map(e =>
            s"$prefix$e: "
          ).orElse(prefix)
        }).orElse("")
      }

      val optPath = InterfaceUtil.jo2o(pos.sourcePath()).map { path =>
        val line0 = intValue(pos.line(), -1)
        val pointer0 = intValue(pos.pointer(), -1)
        if line0 >= 0 && pointer0 >= 0 then
          s"$path:$line0:${pointer0 + 1}"
        else
          path
      }

      val normHeader = optPath.map(path =>
        s"${shade(s"-- $normCode$path")}\n"
      ).getOrElse("")

      val optSnippet = {
        val snip = pos.lineContent()
        val space = pos.pointerSpace().orElse("")
        val pointer = intValue(pos.pointer(), -99)
        val endCol = intValue(pos.endColumn(), pointer + 1)
        if snip.nonEmpty && space.nonEmpty && pointer >= 0 && endCol >= 0 then
          val arrowCount = math.max(1, math.min(endCol - pointer, snip.length - space.length))
          Some(
            s"""$snip
               |$space${"^" * arrowCount}""".stripMargin
          )
        else
          None
      }

      val content = optSnippet.match {
        case Some(snippet) =>
          val initial = {
            s"""$snippet
               |$base
               |""".stripMargin
          }
          val snippetLine = intValue(pos.line(), -1)
          if snippetLine >= 0 then {
            // add margin with line number
            val lines = initial.linesWithSeparators.toVector
            val pre = snippetLine.toString
            val rest0 = " " * pre.length
            val rest = pre +: Vector.fill(lines.size - 1)(rest0)
            rest.lazyZip(lines).map((pre, line) => shade(s"$pre │") + line).mkString
          } else {
            initial
          }
        case None =>
          base
      }

      normHeader + content
    }

    /** Implements a transformation that returns the same list if the mapper has no effect */
    private def transformActions(
        actions0: java.util.List[xsbti.Action],
        mapper: xsbti.Position => xsbti.Position
    ): JOrSList[xsbti.Action] = {
      if actions0.iterator().asScala.exists(a =>
          a.edit().changes().iterator().asScala.exists(e =>
            mapper(e.position()) ne e.position()
          )
        )
      then {
        actions0.iterator().asScala.map(transformAction(_, mapper)).toList
      } else {
        actions0
      }
    }

    /** Implements a transformation that returns the same list if the mapper has no effect */
    private def transformRelateds(
        related0: java.util.List[xsbti.DiagnosticRelatedInformation],
        mapper: xsbti.Position => xsbti.Position
    ): JOrSList[xsbti.DiagnosticRelatedInformation] = {

      if related0.iterator().asScala.exists(r => mapper(r.position()) ne r.position()) then
        related0.iterator().asScala.map(transformRelated(_, mapper)).toList
      else
        related0
    }

    private def transformRelated(
        related0: xsbti.DiagnosticRelatedInformation,
        mapper: xsbti.Position => xsbti.Position
    ): xsbti.DiagnosticRelatedInformation = {
      InterfaceUtil.diagnosticRelatedInformation(mapper(related0.position()), related0.message())
    }

    private def transformAction(
        action0: xsbti.Action,
        mapper: xsbti.Position => xsbti.Position
    ): xsbti.Action = {
      InterfaceUtil.action(
        title = action0.title(),
        description = InterfaceUtil.jo2o(action0.description()),
        edit = transformEdit(action0.edit(), mapper)
      )
    }

    private def transformEdit(
        edit0: xsbti.WorkspaceEdit,
        mapper: xsbti.Position => xsbti.Position
    ): xsbti.WorkspaceEdit = {
      InterfaceUtil.workspaceEdit(
        edit0.changes().iterator().asScala.map(transformTEdit(_, mapper)).toList
      )
    }

    private def transformTEdit(
        edit0: xsbti.TextEdit,
        mapper: xsbti.Position => xsbti.Position
    ): xsbti.TextEdit = {
      InterfaceUtil.textEdit(
        position = mapper(edit0.position()),
        newText = edit0.newText()
      )
    }
  }

  // copied from ModuleUtils

}
