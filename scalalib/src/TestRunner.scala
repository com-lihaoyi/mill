package mill.scalalib
import ammonite.util.Colors
import mill.Agg
import mill.api.{DummyReporter, TestReporter}
import mill.modules.Jvm
import mill.scalalib.Lib.discoverTests
import mill.util.{Ctx, PrintLogger}
import mill.util.JsonFormatters._
import sbt.testing._
import mill.scalalib.api._

import scala.collection.mutable
object TestRunner {


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
          true,
          if(colored == "true") Colors.Default
          else Colors.BlackWhite,
          System.out,
          System.err,
          System.err,
          System.in,
          debugEnabled = false
        )
        val home = os.Path(homeStr)
      }
      val result = runTests(
        frameworkInstances = TestRunner.frameworks(frameworks),
        entireClasspath = Agg.from(classpath.map(os.Path(_))),
        testClassfilePath = Agg(os.Path(testCp)),
        args = arguments,
        DummyReporter
      )(ctx)

      // Clear interrupted state in case some badly-behaved test suite
      // dirtied the thread-interrupted flag and forgot to clean up. Otherwise
      // that flag causes writing the results to disk to fail
      Thread.interrupted()
      ammonite.ops.write(os.Path(outputPath), upickle.default.write(result))
    }catch{case e: Throwable =>
      println(e)
      e.printStackTrace()
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }

  def runTests(frameworkInstances: ClassLoader => Seq[sbt.testing.Framework],
               entireClasspath: Agg[os.Path],
               testClassfilePath: Agg[os.Path],
               args: Seq[String],
               testReporter: TestReporter)
              (implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[mill.scalalib.TestRunner.Result]) = {
    //Leave the context class loader set and open so that shutdown hooks can access it
    Jvm.inprocess(entireClasspath, classLoaderOverrideSbtTesting = true, isolated = true, closeContextClassLoaderWhenDone = false, cl => {
      val frameworks = frameworkInstances(cl)

      val events = mutable.Buffer.empty[Event]

      val doneMessages = frameworks.map{ framework =>
        val runner = framework.runner(args.toArray, Array[String](), cl)

        val testClasses = discoverTests(cl, framework, testClassfilePath)

        val tasks = runner.tasks(
          for ((cls, fingerprint) <- testClasses.toArray)
            yield new TaskDef(cls.getName.stripSuffix("$"), fingerprint, true, Array(new SuiteSelector))
        )

        val taskQueue = tasks.to[mutable.Queue]
        while (taskQueue.nonEmpty){
          val next = taskQueue.dequeue().execute(
            new EventHandler {
              def handle(event: Event) = {
                testReporter.logStart(event)
                events.append(event)
              }
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
          taskQueue.enqueue(next:_*)
        }
        runner.done()
      }

      val results = for(e <- events) yield {
        val ex = if (e.throwable().isDefined) Some(e.throwable().get) else None
        mill.scalalib.TestRunner.Result(
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

  def frameworks(frameworkNames: Seq[String])(cl: ClassLoader): Seq[sbt.testing.Framework] = {
    frameworkNames.map { name =>
      cl.loadClass(name).newInstance().asInstanceOf[sbt.testing.Framework]
    }
  }

  case class Result(fullyQualifiedName: String,
                    selector: String,
                    duration: Long,
                    status: String,
                    exceptionName: Option[String] = None,
                    exceptionMsg: Option[String] = None,
                    exceptionTrace: Option[Seq[StackTraceElement]] = None)

  object Result{
    implicit def resultRW: upickle.default.ReadWriter[Result] = upickle.default.macroRW[Result]
  }
}
