package mill.scalalib

import java.io.FileInputStream
import java.lang.annotation.Annotation
import java.net.URLClassLoader
import java.util.zip.ZipInputStream

import ammonite.ops.{Path, ls, pwd}
import mill.util.Ctx.LogCtx
import mill.util.PrintLogger
import sbt.testing._
import upickle.Js
import mill.util.JsonFormatters._
import scala.collection.mutable

object TestRunner {
  def listClassFiles(base: Path): Iterator[String] = {
    if (base.isDir) ls.rec(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }
  def runTests(cl: ClassLoader, framework: Framework, classpath: Seq[Path]) = {


    val fingerprints = framework.fingerprints()
    val testClasses = classpath.flatMap { base =>
      listClassFiles(base).flatMap { path =>
        val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
        fingerprints.find {
          case f: SubclassFingerprint =>
            cl.loadClass(f.superclassName()).isAssignableFrom(cls)
          case f: AnnotatedFingerprint =>
            cls.isAnnotationPresent(
              cl.loadClass(f.annotationName()).asInstanceOf[Class[Annotation]]
            )
        }.map { f => (cls, f) }
      }
    }
    testClasses
  }
  def main(args: Array[String]): Unit = {
    val result = apply(
      frameworkName = args(0),
      entireClasspath = args(1).split(" ").map(Path(_)),
      testClassfilePath = args(2).split(" ").map(Path(_)),
      args = args(3) match{ case "" => Nil case x => x.split(" ").toList }
    )(new PrintLogger(true))
    val outputPath = args(4)

    ammonite.ops.write(Path(outputPath), upickle.default.write(result))

    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }
  def apply(frameworkName: String,
            entireClasspath: Seq[Path],
            testClassfilePath: Seq[Path],
            args: Seq[String])
           (implicit ctx: LogCtx): (String, Seq[Result]) = {
    val outerClassLoader = getClass.getClassLoader
    val cl = new URLClassLoader(
      entireClasspath.map(_.toIO.toURI.toURL).toArray,
      ClassLoader.getSystemClassLoader().getParent()){
      override def findClass(name: String) = {
        if (name.startsWith("sbt.testing.")){
          outerClassLoader.loadClass(name)
        }else{
          super.findClass(name)
        }
      }
    }

    val framework = cl.loadClass(frameworkName)
      .newInstance()
      .asInstanceOf[sbt.testing.Framework]

    val testClasses = runTests(cl, framework, testClassfilePath)

    val runner = framework.runner(args.toArray, args.toArray, cl)

    val tasks = runner.tasks(
      for((cls, fingerprint) <- testClasses.toArray)
      yield {
        new TaskDef(cls.getName.stripSuffix("$"), fingerprint, true, Array())
      }
    )
    val events = mutable.Buffer.empty[Event]
    for(t <- tasks){
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
        e.status(),
        ex.map(_.getClass.getName),
        ex.map(_.getMessage),
        ex.map(_.getStackTrace)
      )
    }
    (doneMsg, results)
  }

  case class Result(fullyQualifiedName: String,
                    selector: String,
                    duration: Long,
                    status: Status,
                    exceptionName: Option[String],
                    exceptionMsg: Option[String],
                    exceptionTrace: Option[Seq[StackTraceElement]])

  object Result{
    implicit def resultRW: upickle.default.ReadWriter[Result] = upickle.default.macroRW[Result]
    implicit def statusRW: upickle.default.ReadWriter[Status] = upickle.default.ReadWriter[Status](
      {
        case Status.Success => Js.Str("Success")
        case Status.Error => Js.Str("Error")
        case Status.Failure => Js.Str("Failure")
        case Status.Skipped => Js.Str("Skipped")
        case Status.Ignored => Js.Str("Ignored")
        case Status.Canceled => Js.Str("Canceled")
        case Status.Pending => Js.Str("Pending")
      },
      {
        case Js.Str("Success") => Status.Success
        case Js.Str("Error") => Status.Error
        case Js.Str("Failure") => Status.Failure
        case Js.Str("Skipped") => Status.Skipped
        case Js.Str("Ignored") => Status.Ignored
        case Js.Str("Canceled") => Status.Canceled
        case Js.Str("Pending") => Status.Pending
      }
    )
  }

}
