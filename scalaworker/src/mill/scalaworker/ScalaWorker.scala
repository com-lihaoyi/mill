package mill.scalaworker

import java.io.{File, FileInputStream}
import java.lang.annotation.Annotation
import java.net.URLClassLoader
import java.util.Optional
import java.util.zip.ZipInputStream

import ammonite.ops.{Path, exists, ls, mkdir}
import ammonite.util.Colors
import mill.Agg
import mill.define.Worker
import mill.eval.PathRef
import mill.modules.Jvm
import mill.scalalib.{CompilationResult, TestRunner}
import xsbti.compile.{CompilerCache => _, FileAnalysisStore => _, ScalaInstance => _, _}
import mill.scalalib.Lib.grepJar
import mill.scalalib.TestRunner.Result
import mill.util.{Ctx, PrintLogger}
import sbt.internal.inc._
import sbt.internal.util.{ConsoleOut, MainAppender}
import sbt.testing._
import sbt.util.LogExchange

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
      val result = new ScalaWorker(null).apply(
        frameworkInstance = TestRunner.framework(args(0)),
        entireClasspath = Agg.from(args(1).split(" ").map(Path(_))),
        testClassfilePath = Agg.from(args(2).split(" ").map(Path(_))),
        args = args(3) match{ case "" => Nil case x => x.split(" ").toList }
      )(new PrintLogger(
        args(5) == "true",
        if(args(5) == "true") Colors.Default
        else Colors.BlackWhite,
        System.out,
        System.err,
        System.err
      ))
      val outputPath = args(4)

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
class ScalaWorker(ctx0: mill.util.Ctx) extends mill.scalalib.ScalaWorkerApi{
  @volatile var scalaClassloaderCache = Option.empty[(Long, ClassLoader)]
  @volatile var scalaInstanceCache = Option.empty[(Long, ScalaInstance)]

  def compileScala(scalaVersion: String,
                   sources: Agg[Path],
                   compileClasspath: Agg[Path],
                   compilerClasspath: Agg[Path],
                   pluginClasspath: Agg[Path],
                   compilerBridge: Path,
                   scalacOptions: Seq[String],
                   scalacPluginClasspath: Agg[Path],
                   javacOptions: Seq[String],
                   upstreamCompileOutput: Seq[CompilationResult])
                  (implicit ctx: mill.util.Ctx): CompilationResult = {
    val compileClasspathFiles = compileClasspath.map(_.toIO).toArray

    val compilerJars = compilerClasspath.toArray.map(_.toIO)
    val pluginJars = pluginClasspath.toArray.map(_.toIO)

    val compilerClassloaderSig = compilerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    val scalaInstanceSig =
      compilerClassloaderSig + pluginClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum

    val compilerClassLoader = scalaClassloaderCache match{
      case Some((k, v)) if k == compilerClassloaderSig => v
      case _ =>
        val classloader = new URLClassLoader(compilerJars.map(_.toURI.toURL), null)
        scalaClassloaderCache = Some((compilerClassloaderSig, classloader))
        classloader
    }

    val scalaInstance = scalaInstanceCache match{
      case Some((k, v)) if k == scalaInstanceSig => v
      case _ =>
        val scalaInstance = new ScalaInstance(
          version = scalaVersion,
          loader = new URLClassLoader(pluginJars.map(_.toURI.toURL), compilerClassLoader),
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

    val newResult = ic.compile(
      ic.inputs(
        classpath = classesIODir +: compileClasspathFiles,
        sources = for{
          root <- sources.toArray
          if exists(root)
          path <- ls.rec(root)
          if path.isFile && (path.ext == "scala" || path.ext == "java")
        } yield path.toIO,
        classesDirectory = classesIODir,
        scalacOptions = (scalacPluginClasspath.map(jar => s"-Xplugin:${jar}") ++  scalacOptions).toArray,
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

    CompilationResult(zincFile, PathRef(classesDir))
  }

  def apply(frameworkInstance: ClassLoader => sbt.testing.Framework,
            entireClasspath: Agg[Path],
            testClassfilePath: Agg[Path],
            args: Seq[String])
           (implicit ctx: mill.util.Ctx.LogCtx): (String, Seq[Result]) = {

    sbt.testing.Status.values().foreach { s: Status => () }

    Jvm.inprocess(entireClasspath, classLoaderOverrideSbtTesting = true, cl => {
      val framework = frameworkInstance(cl)

      val testClasses = runTests(cl, framework, testClassfilePath)

      val runner = framework.runner(args.toArray, args.toArray, cl)

      val tasks = runner.tasks(
        for ((cls, fingerprint) <- testClasses.toArray)
        yield new TaskDef(cls.getName.stripSuffix("$"), fingerprint, true, Array(new SuiteSelector))
      )
      val events = mutable.Buffer.empty[Event]
      for (t <- tasks) {
        t.execute(
          new EventHandler {
            def handle(event: Event) = events.append(event)
          },
          Array(
            new Logger {
              def debug(msg: String) = ctx.log.info(msg)

              def error(msg: String) = ctx.log.error(msg)

              def ansiCodesSupported() = true

              def warn(msg: String) = ctx.log.info(msg)

              def trace(t: Throwable) = t.printStackTrace(ctx.log.outputStream)

              def info(msg: String) = ctx.log.info(msg)
            })
        )
      }
      val doneMsg = runner.done()
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
      (doneMsg, results)
    })

  }
  def listClassFiles(base: Path): Iterator[String] = {
    if (base.isDir) ls.rec(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }
  def runTests(cl: ClassLoader, framework: Framework, classpath: Agg[Path]) = {


    val fingerprints = framework.fingerprints()
    val testClasses = classpath.flatMap { base =>
      listClassFiles(base).flatMap { path =>
        val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
        fingerprints.find {
          case f: SubclassFingerprint =>

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
