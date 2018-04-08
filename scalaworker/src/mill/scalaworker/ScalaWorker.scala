package mill.scalaworker

import java.io.{File, FileInputStream}
import java.lang.annotation.Annotation
import java.net.URLClassLoader
import java.util.Optional
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider

import ammonite.ops.{Path, exists, ls, mkdir, rm, up}
import ammonite.util.Colors
import mill.Agg
import mill.define.Worker
import mill.eval.PathRef
import mill.modules.Jvm
import mill.scalalib.{CompilationResult, Lib, TestRunner}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.Lib.grepJar
import mill.scalalib.TestRunner.Result
import mill.util.{Ctx, PrintLogger}
import sbt.internal.inc._
import sbt.internal.inc.classfile.Analyze
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.internal.inc.javac.JavaCompiler
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.io.PathFinder
import sbt.testing._
import sbt.util.{InterfaceUtil, LogExchange}
import xsbti.AnalysisCallback

import scala.collection.mutable

case class MockedLookup(am: File => Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: File): DefinesClass =
    Locate.definesClass(classpathEntry)
}

object ScalaWorker{

  def main(args: Array[String]): Unit = {
    try{
      var i = 0
      def readArray() = {
        val count = args(i).toInt
        val slice = args.slice(i + 1, i + count + 1)
        i = i + count + 1
        slice
      }
      val frameworks = readArray()
      val classpath = readArray()
      val arguments = readArray()
      val outputPath = args(i + 0)
      val colored = args(i + 1)
      val testCp = args(i + 2)
      val homeStr = args(i + 3)
      val ctx = new Ctx.Log with Ctx.Home {
        val log = PrintLogger(
          colored == "true",
          if(colored == "true") Colors.Default
          else Colors.BlackWhite,
          System.out,
          System.err,
          System.err,
          System.in
        )
        val home = Path(homeStr)
      }
      val result = new ScalaWorker(null, null).runTests(
        frameworkInstances = TestRunner.frameworks(frameworks),
        entireClasspath = Agg.from(classpath.map(Path(_))),
        testClassfilePath = Agg(Path(testCp)),
        args = arguments
      )(ctx)

      ammonite.ops.write(Path(outputPath), upickle.default.write(result))
    }catch{case e: Throwable =>
      println(e)
      e.printStackTrace()
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }
}

class ScalaWorker(ctx0: mill.util.Ctx,
                  compilerBridgeClasspath: Array[String]) extends mill.scalalib.ScalaWorkerApi{
  @volatile var scalaClassloaderCache = Option.empty[(Long, ClassLoader)]
  @volatile var scalaInstanceCache = Option.empty[(Long, ScalaInstance)]

  def compileZincBridge(scalaVersion: String,
                        sourcesJar: Path,
                        compilerJars: Array[File]) = {
    val workingDir = ctx0.dest / scalaVersion
    val compiledDest = workingDir / 'compiled
    if (!exists(workingDir)) {

      println("Compiling compiler interface...")

      mkdir(workingDir)
      mkdir(compiledDest)

      val sourceFolder = mill.modules.Util.unpackZip(sourcesJar)(workingDir)
      val classloader = mill.util.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)(ctx0)
      val scalacMain = classloader.loadClass("scala.tools.nsc.Main")
      val argsArray = Array[String](
        "-d", compiledDest.toString,
        "-classpath", (compilerJars ++ compilerBridgeClasspath).mkString(File.pathSeparator)
      ) ++ ls.rec(sourceFolder.path).filter(_.ext == "scala").map(_.toString)

      scalacMain.getMethods
        .find(_.getName == "process")
        .get
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


  def compileScala(scalaVersion: String,
                   sources: Agg[Path],
                   compilerBridgeSources: Path,
                   compileClasspath: Agg[Path],
                   compilerClasspath: Agg[Path],
                   scalacOptions: Seq[String],
                   scalacPluginClasspath: Agg[Path],
                   javacOptions: Seq[String],
                   upstreamCompileOutput: Seq[CompilationResult])
                  (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray
    val compilerJars = compilerClasspath.toArray.map(_.toIO)

    val compilerBridge = compileZincBridge(scalaVersion, compilerBridgeSources, compilerJars)

    val pluginJars = scalacPluginClasspath.toArray.map(_.toIO)

    val compilerClassloaderSig = compilerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    val scalaInstanceSig =
      compilerClassloaderSig + scalacPluginClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum

    val compilerClassLoader = scalaClassloaderCache match{
      case Some((k, v)) if k == compilerClassloaderSig => v
      case _ =>
        val classloader = mill.util.ClassLoader.create(compilerJars.map(_.toURI.toURL), null)
        scalaClassloaderCache = Some((compilerClassloaderSig, classloader))
        classloader
    }

    val scalaInstance = scalaInstanceCache match{
      case Some((k, v)) if k == scalaInstanceSig => v
      case _ =>
        val scalaInstance = new ScalaInstance(
          version = scalaVersion,
          loader = mill.util.ClassLoader.create(pluginJars.map(_.toURI.toURL), compilerClassLoader),
          libraryJar = grepJar(compilerClasspath, s"scala-library-$scalaVersion.jar"),
          compilerJar = grepJar(compilerClasspath, s"scala-compiler-$scalaVersion.jar"),
          allJars = compilerJars ++ pluginJars,
          explicitActual = None
        )
        scalaInstanceCache = Some((scalaInstanceSig, scalaInstance))
        scalaInstance
    }

    mkdir(ctx.dest)

    val ic = new sbt.internal.inc.IncrementalCompilerImpl()

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

    try {
      val newResult = ic.compile(
        ic.inputs(
          classpath = classesIODir +: compileClasspathFiles,
          sources = sources.toArray.map(_.toIO),
          classesDirectory = classesIODir,
          scalacOptions = (scalacPluginClasspath.map(jar => s"-Xplugin:${jar}") ++ scalacOptions).toArray,
          javacOptions = javacOptions.toArray,
          maxErrors = 10,
          sourcePositionMappers = Array(),
          order = CompileOrder.Mixed,
          compilers = ic.compilers(
            scalaInstance,
            ClasspathOptionsUtil.boot,
            None,
            ZincUtil.scalaCompiler(scalaInstance, compilerBridge.toIO)
          ),
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
        ),
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

  def runTests(frameworkInstances: ClassLoader => Seq[sbt.testing.Framework],
               entireClasspath: Agg[Path],
               testClassfilePath: Agg[Path],
               args: Seq[String])
              (implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[Result]) = {
    Jvm.inprocess(entireClasspath, classLoaderOverrideSbtTesting = true, cl => {
      val frameworks = frameworkInstances(cl)

      val events = mutable.Buffer.empty[Event]

      val doneMessages = frameworks.map { framework =>
        val runner = framework.runner(args.toArray, args.toArray, cl)

        val testClasses = discoverTests(cl, framework, testClassfilePath)

        val tasks = runner.tasks(
          for ((cls, fingerprint) <- testClasses.toArray)
            yield new TaskDef(cls.getName.stripSuffix("$"), fingerprint, true, Array(new SuiteSelector))
        )

        for (t <- tasks) {
          t.execute(
            new EventHandler {
              def handle(event: Event) = events.append(event)
            },
            Array(
              new Logger {
                def debug(msg: String) = ctx.log.outputStream.println(msg)

                def error(msg: String) = ctx.log.outputStream.println(msg)

                def ansiCodesSupported() = true

                def warn(msg: String) = ctx.log.outputStream.println(msg)

                def trace(t: Throwable) = t.printStackTrace(ctx.log.outputStream)

                def info(msg: String) = ctx.log.outputStream.println(msg)
              })
          )
        }
        ctx.log.outputStream.println(runner.done())
      }

      val results = for(e <- events) yield {
        val ex = if (e.throwable().isDefined) Some(e.throwable().get) else None
        Result(
          e.fullyQualifiedName(),
          e.selector() match{
            case s: NestedSuiteSelector => s.suiteId()
            case s: NestedTestSelector => s.suiteId() + "." + s.testName()
            case s: SuiteSelector => s.toString
            case s: TestSelector => s.testName()
            case s: TestWildcardSelector => s.testWildcard()
          },
          e.duration(),
          e.status().toString,
          ex.map(_.getClass.getName),
          ex.map(_.getMessage),
          ex.map(_.getStackTrace)
        )
      }

      (doneMessages.mkString("\n"), results)
    })

  }
  def listClassFiles(base: Path): Iterator[String] = {
    if (base.isDir) ls.rec(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }
  def discoverTests(cl: ClassLoader, framework: Framework, classpath: Agg[Path]) = {


    val fingerprints = framework.fingerprints()
    val testClasses = classpath.flatMap { base =>
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      if (!exists(base)) Nil
      else listClassFiles(base).flatMap { path =>
        val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
        fingerprints.find {
          case f: SubclassFingerprint =>
            !cls.isInterface &&
              (f.isModule == cls.getName.endsWith("$")) &&
              cl.loadClass(f.superclassName()).isAssignableFrom(cls)
          case f: AnnotatedFingerprint =>
            (f.isModule == cls.getName.endsWith("$")) &&
              cls.isAnnotationPresent(
                cl.loadClass(f.annotationName()).asInstanceOf[Class[Annotation]]
              )
        }.map { f => (cls, f) }
      }
    }
    testClasses
  }
}
