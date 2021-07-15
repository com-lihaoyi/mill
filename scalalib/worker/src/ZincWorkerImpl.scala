package mill.scalalib.worker

import java.io.File
import java.util.Optional
import mill.api.Loose.Agg
import mill.api.{
  BuildProblemReporter,
  KeyedLockedCache,
  PathRef,
  Problem,
  ProblemPosition,
  Severity
}
import mill.scalalib.api.Util.{
  grepJar,
  isDotty,
  isDottyOrScala3,
  isScala3,
  isScala3Milestone,
  scalaBinaryVersion
}
import mill.scalalib.api.{CompilationResult, ZincWorkerApi}
import sbt.internal.inc._
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.util.LogExchange
import xsbti.{PathBasedFile, VirtualFile}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}

import scala.ref.WeakReference

case class MockedLookup(am: VirtualFile => Optional[CompileAnalysis])
    extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: VirtualFile): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: VirtualFile): DefinesClass =
    Locate.definesClass(classpathEntry)
}

class ZincProblem(base: xsbti.Problem) extends Problem {
  override def category: String = base.category()

  override def severity: Severity = base.severity() match {
    case xsbti.Severity.Info => mill.api.Info
    case xsbti.Severity.Warn => mill.api.Warn
    case xsbti.Severity.Error => mill.api.Error
  }

  override def message: String = base.message()

  override def position: ProblemPosition = new ZincProblemPosition(base.position())
}

class ZincProblemPosition(base: xsbti.Position) extends ProblemPosition {

  object JavaOptionConverter {
    implicit def convertInt(x: Optional[Integer]): Option[Int] =
      if (x.isPresent) Some(x.get().intValue()) else None
    implicit def convert[T](x: Optional[T]): Option[T] = if (x.isPresent) Some(x.get()) else None
  }

  import JavaOptionConverter._

  override def line: Option[Int] = base.line()

  override def lineContent: String = base.lineContent()

  override def offset: Option[Int] = base.offset()

  override def pointer: Option[Int] = base.pointer()

  override def pointerSpace: Option[String] = base.pointerSpace()

  override def sourcePath: Option[String] = base.sourcePath()

  override def sourceFile: Option[File] = base.sourceFile()

  override def startOffset: Option[Int] = base.startOffset()

  override def endOffset: Option[Int] = base.endOffset()

  override def startLine: Option[Int] = base.startLine()

  override def startColumn: Option[Int] = base.startColumn()

  override def endLine: Option[Int] = base.endLine()

  override def endColumn: Option[Int] = base.endColumn()
}

class ZincWorkerImpl(
    compilerBridge: Either[
      (ZincWorkerApi.Ctx, (String, String) => (Option[Array[os.Path]], os.Path)),
      String => os.Path
    ],
    libraryJarNameGrep: (Agg[os.Path], String) => os.Path,
    compilerJarNameGrep: (Agg[os.Path], String) => os.Path,
    compilerCache: KeyedLockedCache[Compilers],
    compileToJar: Boolean
) extends ZincWorkerApi {
  private val ic = new sbt.internal.inc.IncrementalCompilerImpl()
  lazy val javaOnlyCompilers = {
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
      new ScalaInstance("", null, null, dummyFile, dummyFile, new Array(0), Some("")),
      dummyFile,
      classpathOptions // this is used for javac too
    )

    ic.compilers(
      instance = null,
      classpathOptions,
      None,
      scalac
    )
  }

  val compilerBridgeLocks = collection.mutable.Map.empty[String, Object]

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[os.Path],
      scalacPluginClasspath: Agg[os.Path],
      args: Seq[String]
  )(implicit ctx: ZincWorkerApi.Ctx): Boolean = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath
    ) { compilers: Compilers =>
      if (isDotty(scalaVersion) || isScala3Milestone(scalaVersion)) {
        // dotty 0.x and scala 3 milestones use the dotty-doc tool
        val dottydocClass =
          compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.dottydoc.DocDriver")
        val dottydocMethod = dottydocClass.getMethod("process", classOf[Array[String]])
        val reporter = dottydocMethod.invoke(dottydocClass.newInstance(), args.toArray)
        val hasErrorsMethod = reporter.getClass().getMethod("hasErrors")
        !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
      } else if (isScala3(scalaVersion)) {
        val scaladocClass =
          compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.scaladoc.Main")
        val scaladocMethod = scaladocClass.getMethod("run", classOf[Array[String]])
        val reporter = scaladocMethod.invoke(scaladocClass.newInstance(), args.toArray)
        val hasErrorsMethod = reporter.getClass().getMethod("hasErrors")
        !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
      } else {
        val scaladocClass =
          compilers.scalac().scalaInstance().loader().loadClass("scala.tools.nsc.ScalaDoc")
        val scaladocMethod = scaladocClass.getMethod("process", classOf[Array[String]])
        scaladocMethod.invoke(scaladocClass.newInstance(), args.toArray).asInstanceOf[Boolean]
      }
    }
  }

  /** Compile the SBT/Zinc compiler bridge in the `compileDest` directory */
  def compileZincBridge(
      ctx0: ZincWorkerApi.Ctx,
      workingDir: os.Path,
      compileDest: os.Path,
      scalaVersion: String,
      compilerJars: Array[File],
      compilerBridgeClasspath: Array[os.Path],
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
    val classloader = mill.api.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)

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
      (compilerJars ++ compilerBridgeClasspath).mkString(File.pathSeparator)
    ) ++ sources.map(_.toString)

    val allScala = sources.forall(_.ext == "scala")
    val allJava = sources.forall(_.ext == "java")
    if (allJava) {
      import scala.sys.process._
      (Seq("javac") ++ argsArray).!
    } else if (allScala) {
      val compilerMain = classloader.loadClass(
        if (isDottyOrScala3(scalaVersion)) "dotty.tools.dotc.Main"
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
      compilerClasspath: Agg[os.Path]
  ): os.Path = {
    compilerBridge match {
      case Right(compiled) => compiled(scalaVersion)
      case Left((ctx0, bridgeProvider)) =>
        val workingDir = ctx0.dest / s"zinc-${Versions.zinc}" / scalaVersion
        val lock = synchronized(compilerBridgeLocks.getOrElseUpdate(scalaVersion, new Object()))
        val compiledDest = workingDir / "compiled"
        lock.synchronized {
          if (os.exists(compiledDest / "DONE")) compiledDest
          else {
            val (cp, bridgeJar) = bridgeProvider(scalaVersion, scalaOrganization)
            cp match {
              case None => bridgeJar
              case Some(bridgeClasspath) =>
                val compilerJars = compilerClasspath.toArray.map(_.toIO)
                compileZincBridge(
                  ctx0,
                  workingDir,
                  compiledDest,
                  scalaVersion,
                  compilerJars,
                  bridgeClasspath,
                  bridgeJar
                )
                os.write(compiledDest / "DONE", "")
                compiledDest
            }
          }

        }
    }

  }

  def discoverMainClasses(compilationResult: CompilationResult)(implicit
      ctx: ZincWorkerApi.Ctx
  ): Seq[String] = {
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
      reporter: Option[BuildProblemReporter]
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] = {

    for (
      res <- compileJava0(
        upstreamCompileOutput.map(c => (c.analysisFile, c.classes.path)),
        sources,
        compileClasspath,
        javacOptions,
        reporter
      )
    ) yield CompilationResult(res._1, PathRef(res._2))
  }
  def compileJava0(
      upstreamCompileOutput: Seq[(os.Path, os.Path)],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      reporter: Option[BuildProblemReporter]
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
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

  def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Agg[os.Path],
      scalacPluginClasspath: Agg[os.Path],
      reporter: Option[BuildProblemReporter]
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult] = {

    for (
      res <- compileMixed0(
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
      )
    ) yield CompilationResult(res._1, PathRef(res._2))
  }

  def compileMixed0(
      upstreamCompileOutput: Seq[(os.Path, os.Path)],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Agg[os.Path],
      scalacPluginClasspath: Agg[os.Path],
      reporter: Option[BuildProblemReporter]
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
    withCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath
    ) { compilers: Compilers =>
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
  private[this] val classloaderCache =
    collection.mutable.LinkedHashMap.empty[Long, WeakReference[ClassLoader]]

  def getCachedClassLoader(compilersSig: Long, combinedCompilerJars: Array[java.io.File])(implicit
      ctx: ZincWorkerApi.Ctx
  ) = {
    classloaderCache.synchronized {
      classloaderCache.get(compilersSig) match {
        case Some(WeakReference(cl)) => cl
        case _ =>
          // the Scala compiler must load the `xsbti.*` classes from the same loader than `ZincWorkerImpl`
          val sharedPrefixes = Seq("xsbti")
          val cl = mill.api.ClassLoader.create(
            combinedCompilerJars.map(_.toURI.toURL),
            parent = null,
            sharedLoader = getClass.getClassLoader,
            sharedPrefixes
          )
          classloaderCache.update(compilersSig, WeakReference(cl))
          cl
      }
    }
  }

  private def withCompilers[T](
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Agg[os.Path],
      scalacPluginClasspath: Agg[os.Path]
  )(f: Compilers => T)(implicit ctx: ZincWorkerApi.Ctx) = {
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

    compilerCache.withCachedValue(compilersSig) {
      val compilerJar =
        if (isDotty(scalaVersion))
          grepJar(
            compilerClasspath,
            s"dotty-compiler_${scalaBinaryVersion(scalaVersion)}",
            scalaVersion
          )
        else if (isScala3(scalaVersion))
          grepJar(
            compilerClasspath,
            s"scala3-compiler_${scalaBinaryVersion(scalaVersion)}",
            scalaVersion
          )
        else
          compilerJarNameGrep(compilerClasspath, scalaVersion)

      val scalaInstance = new ScalaInstance(
        version = scalaVersion,
        loader = getCachedClassLoader(compilersSig, combinedCompilerJars),
        libraryJar = libraryJarNameGrep(
          compilerClasspath,
          // we don't support too outdated dotty versions
          // and because there will be no scala 2.14, so hardcode "2.13." here is acceptable
          if (isDottyOrScala3(scalaVersion)) "2.13." else scalaVersion
        ).toIO,
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

  private def compileInternal(
      upstreamCompileOutput: Seq[(os.Path, os.Path)],
      sources: Agg[os.Path],
      compileClasspath: Agg[os.Path],
      javacOptions: Seq[String],
      scalacOptions: Seq[String],
      compilers: Compilers,
      reporter: Option[BuildProblemReporter]
  )(implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[(os.Path, os.Path)] = {
    os.makeDir.all(ctx.dest)

    val consoleAppender = ConsoleAppender(
      "ZincLogAppender",
      ConsoleOut.printStreamOut(ctx.log.errorStream),
      ctx.log.colored,
      ctx.log.colored,
      _ => None
    )
    val loggerId = Thread.currentThread().getId.toString
    val logger = LogExchange.logger(loggerId)
    LogExchange.unbindLoggerAppenders(loggerId)
    LogExchange.bindLoggerAppenders(loggerId, (consoleAppender -> sbt.util.Level.Info) :: Nil)

    val newReporter = reporter match {
      case None => new ManagedLoggedReporter(10, logger)
      case Some(r) => new ManagedLoggedReporter(10, logger) {
          override def logError(problem: xsbti.Problem): Unit = {
            r.logError(new ZincProblem(problem))
            super.logError(problem)
          }

          override def logWarning(problem: xsbti.Problem): Unit = {
            r.logWarning(new ZincProblem(problem))
            super.logWarning(problem)
          }

          override def logInfo(problem: xsbti.Problem): Unit = {
            r.logInfo(new ZincProblem(problem))
            super.logInfo(problem)
          }

          override def printSummary(): Unit = {
            r.printSummary()
            super.printSummary()
          }
        }
    }
    val analysisMap0 = upstreamCompileOutput.map(_.swap).toMap

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

    val converter = PlainVirtualFileConverter.converter
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
        lookup,
        skip = false,
        zincFile.toNIO,
        new FreshCompilerCache,
        IncOptions.of(),
        newReporter,
        None,
        None,
        Array()
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

      store.set(
        AnalysisContents.create(
          newResult.analysis(),
          newResult.setup()
        )
      )
      mill.api.Result.Success((zincFile, classesDir))
    } catch { case e: CompileFailed => mill.api.Result.Failure(e.toString) }
  }
}
