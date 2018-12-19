package mill.scalalib.worker

import mill.scalalib.worker._

import java.io.File
import java.util.Optional

import mill.api.Loose.Agg
import mill.api.PathRef
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.api.Util.{isDotty, grepJar, scalaBinaryVersion}
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.util.LogExchange
import mill.scalalib.api.CompilationResult
case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: File): DefinesClass =
    Locate.definesClass(classpathEntry)
}

trait KeyedCache[T]{
  def withCachedValue[V](key: Long)(f: => T)(f2: T => V): V
}

object KeyedCache{
  class RandomBoundedCache[T](hotParallelism: Int, coldCacheSize: Int) extends KeyedCache[T]{
    private[this] val random = new scala.util.Random(313373)
    val available = new java.util.concurrent.Semaphore(hotParallelism)

    // Awful asymptotic complexity, but our caches are tiny n < 10 so it doesn't matter
    var cache = Array.fill[Option[(Long, T)]](coldCacheSize)(None)

    def withCachedValue[V](key: Long)(f: => T)(f2: T => V): V = {
      available.acquire()
      val pickedValue = synchronized{
        cache.indexWhere(_.exists(_._1 == key)) match {
          case -1 => f
          case i =>
            val (k, v) = cache(i).get
            cache(i) = None
            v
        }
      }
      val result = f2(pickedValue)
      synchronized{
        cache.indexWhere(_.isEmpty) match{
          // Random eviction #YOLO
          case -1 => cache(random.nextInt(cache.length)) = Some((key, pickedValue))
          case i => cache(i) = Some((key, pickedValue))
        }
      }

      available.release()
      result
    }
  }
}

object ZincWorkerImpl{
  type Ctx = mill.api.Ctx.Dest with mill.api.Ctx.Log with mill.api.Ctx.Home
}
class ZincWorkerImpl(compilerBridge: Either[(ZincWorkerImpl.Ctx, Array[os.Path], String => os.Path), String => os.Path],
                     libraryJarNameGrep: (Agg[os.Path], String) => os.Path = grepJar(_, "scala-library", _),
                     compilerJarNameGrep: (Agg[os.Path], String) => os.Path = grepJar(_, "scala-compiler", _),
                     compilerCache: KeyedCache[Compilers] = new KeyedCache.RandomBoundedCache(1, 1)) {
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
             compilerClasspath: Agg[os.Path],
             scalacPluginClasspath: Agg[os.Path],
             args: Seq[String])
            (implicit ctx: ZincWorkerImpl.Ctx): Boolean = {
    withCompilers(
      scalaVersion,
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
  def compileZincBridgeIfNeeded(scalaVersion: String, compilerJars: Array[File]): os.Path = {
    compilerBridge match{
      case Right(compiled) => compiled(scalaVersion)
      case Left((ctx0, compilerBridgeClasspath, srcJars)) =>
        val workingDir = ctx0.dest / scalaVersion
        val compiledDest = workingDir / 'compiled
        if (!os.exists(workingDir)) {
          ctx0.log.info("Compiling compiler interface...")

          os.makeDir.all(workingDir)
          os.makeDir.all(compiledDest)

          val sourceFolder = mill.api.IO.unpackZip(srcJars(scalaVersion))(workingDir)
          val classloader = mill.api.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)
          val compilerMain = classloader.loadClass(
            if (isDotty(scalaVersion)) "dotty.tools.dotc.Main"
            else "scala.tools.nsc.Main"
          )
          val argsArray = Array[String](
            "-d", compiledDest.toString,
            "-Ylog-classpath",
            "-classpath", (compilerJars ++ compilerBridgeClasspath).mkString(File.pathSeparator)
          ) ++ os.walk(sourceFolder.path).filter(_.ext == "scala").map(_.toString)

          compilerMain.getMethod("process", classOf[Array[String]])
            .invoke(null, argsArray)
        }
        compiledDest
    }

  }



  def discoverMainClasses(compilationResult: CompilationResult)
                         (implicit ctx: ZincWorkerImpl.Ctx): Seq[String] = {
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
                 (implicit ctx: ZincWorkerImpl.Ctx): mill.api.Result[CompilationResult] = {
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
                   compilerClasspath: Agg[os.Path],
                   scalacPluginClasspath: Agg[os.Path])
                  (implicit ctx: ZincWorkerImpl.Ctx): mill.api.Result[CompilationResult] = {
    withCompilers(
      scalaVersion,
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
                               compilerClasspath: Agg[os.Path],
                               scalacPluginClasspath: Agg[os.Path])
                              (f: Compilers => T)
                              (implicit ctx: ZincWorkerImpl.Ctx)= {
    val combinedCompilerClasspath = compilerClasspath ++ scalacPluginClasspath
    val combinedCompilerJars = combinedCompilerClasspath.toArray.map(_.toIO)

    val compiledCompilerBridge =
      compileZincBridgeIfNeeded(scalaVersion, compilerClasspath.toArray.map(_.toIO))

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
                             (implicit ctx: ZincWorkerImpl.Ctx): mill.api.Result[CompilationResult] = {
    os.makeDir.all(ctx.dest)

    println("compileClasspath " + compileClasspath)
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

      mill.api.Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
    }catch{case e: CompileFailed => mill.api.Result.Failure(e.toString)}
  }
}
