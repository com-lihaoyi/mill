package mill.testrunner

import mill.api.Loose.Agg
import mill.api.{Ctx, DummyTestReporter, Loose, TestReporter}
import mill.util.Jvm
import mill.util.PrintLogger
import mill.api.JsonFormatters._
import sbt.testing._

import java.io.FileInputStream
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import scala.collection.mutable
import scala.util.{Try, Using}

object TestRunner {

  private object CloseableIterator {
    def apply[T](it: Iterator[T], onClose: () => Unit = () => {}): Iterator[T] with AutoCloseable =
      new Iterator[T] with AutoCloseable {
        override def hasNext: Boolean = it.hasNext
        override def next(): T = it.next()
        override def close(): Unit = onClose()
      }
  }

  def listClassFiles(base: os.Path): Iterator[String] with AutoCloseable = {
    if (os.isDir(base)) {
      val it = os.walk(base).iterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
      CloseableIterator(it)
    } else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      val it = Iterator.continually(zip.getNextEntry)
        .takeWhile(_ != null)
        .map(_.getName)
        .filter(_.endsWith(
          ".class"
        ))
      CloseableIterator(it, () => zip.close())
    }
  }

  def discoverTests(
      cl: ClassLoader,
      framework: Framework,
      classpath: Loose.Agg[os.Path]
  ): Loose.Agg[(Class[_], Fingerprint)] = {

    val fingerprints = framework.fingerprints()

    val testClasses = classpath.flatMap { base =>
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      if (!os.exists(base)) Nil
      else Using.resource(listClassFiles(base)) { classfiles =>
        classfiles.flatMap { path =>
          val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
          val publicConstructorCount =
            cls.getConstructors.count(c => Modifier.isPublic(c.getModifiers))

          if (framework.name() == "Jupiter") {
            // sbt-jupiter-interface ignores fingerprinting since JUnit5 has its own resolving mechanism
            Some((cls, fingerprints.head))
          } else if (
            Modifier.isAbstract(cls.getModifiers) || cls.isInterface || publicConstructorCount > 1
          ) {
            None
          } else {
            (cls.getName.endsWith("$"), publicConstructorCount == 0) match {
              case (true, true) => matchFingerprints(cl, cls, fingerprints, isModule = true)
              case (false, false) => matchFingerprints(cl, cls, fingerprints, isModule = false)
              case _ => None
            }
          }
        }
      }
    }

    testClasses
  }

  def matchFingerprints(
      cl: ClassLoader,
      cls: Class[_],
      fingerprints: Array[Fingerprint],
      isModule: Boolean
  ): Option[(Class[_], Fingerprint)] = {
    fingerprints.find {
      case f: SubclassFingerprint =>
        f.isModule == isModule &&
        cl.loadClass(f.superclassName()).isAssignableFrom(cls)

      case f: AnnotatedFingerprint =>
        val annotationCls = cl.loadClass(f.annotationName()).asInstanceOf[Class[Annotation]]
        f.isModule == isModule &&
        (
          cls.isAnnotationPresent(annotationCls) ||
            cls.getDeclaredMethods.exists(_.isAnnotationPresent(annotationCls)) ||
            cls.getMethods.exists(m =>
              m.isAnnotationPresent(annotationCls) && Modifier.isPublic(m.getModifiers())
            )
        )

    }.map { f => (cls, f) }
  }

  case class TestArgs(
      framework: String,
      classpath: Seq[String],
      arguments: Seq[String],
      sysProps: Map[String, String],
      outputPath: String,
      colored: Boolean,
      testCp: String,
      homeStr: String,
      globSelectors: Seq[String]
  ) {
    def toArgsSeq: Seq[String] =
      Seq(
        Seq(framework) ++
          Seq(classpath.size.toString) ++
          classpath ++
          Seq(arguments.size.toString) ++
          arguments ++
          Seq((sysProps.size * 2).toString) ++
          sysProps.flatMap { case (k, v) => Seq(k, v) } ++
          Seq(globSelectors.size.toString) ++
          globSelectors ++
          Seq(outputPath, colored.toString, testCp, homeStr)
      ).flatten

    def writeArgsFile(argsFile: os.Path): String = {
      os.write.over(
        argsFile,
        data = toArgsSeq.mkString("\n"),
        createFolders = true
      )
      s"@${argsFile.toString()}"
    }
  }

  object TestArgs {

    def parseArgs(args: Array[String]): Try[TestArgs] = {
      args match {
        case Array(fileArg) if fileArg.startsWith("@") =>
          val file = os.Path(fileArg.drop(1), os.pwd)
          parseFile(file)
        case _ => parseArray(args)
      }
    }

    def parseFile(file: os.Path): Try[TestArgs] =
      Try {
        os.read(file).linesIterator.filter(_.trim().nonEmpty).to(Array)
      }.flatMap(parseArray)

    def parseArray(args: Array[String]): Try[TestArgs] = Try {
      var i = 0
      def readArray(): Array[String] = {
        val count = args(i).toInt
        val slice = args.slice(i + 1, i + count + 1)
        i = i + count + 1
        slice
      }
      def readString(): String = {
        val string = args(i)
        i = i + 1
        string
      }
      val frameworks = readString()
      val classpath = readArray()
      val arguments = readArray()
      val sysProps = readArray()
      val globFilters = readArray()
      val outputPath = readString()
      val colored = readString()
      val testCp = readString()
      val homeStr = readString()

      TestArgs(
        frameworks,
        classpath.toIndexedSeq,
        arguments.toIndexedSeq,
        sysProps.grouped(2).foldLeft(Map[String, String]()) { (map, prop) =>
          map.updated(prop(0), prop(1))
        },
        outputPath,
        colored = Seq("true", "1", "on", "yes").contains(colored),
        testCp = testCp,
        homeStr = homeStr,
        globFilters.toIndexedSeq
      )
    }
  }

  def main(args: Array[String]): Unit = {
    try {
      val testArgs = TestArgs.parseArgs(args).get
      val ctx = new Ctx.Log with Ctx.Home {
        val log = PrintLogger(
          testArgs.colored,
          true,
          if (testArgs.colored) fansi.Color.Blue
          else fansi.Attrs.Empty,
          if (testArgs.colored) fansi.Color.Red
          else fansi.Attrs.Empty,
          System.out,
          System.err,
          System.err,
          System.in,
          debugEnabled = false,
          context = ""
        )
        val home = os.Path(testArgs.homeStr)
      }
      ctx.log.debug(s"Setting ${testArgs.sysProps.size} system properties")
      testArgs.sysProps.foreach { case (k, v) => System.setProperty(k, v) }

      val filter = globFilter(testArgs.globSelectors)

      val result = runTestFramework(
        frameworkInstances = TestRunner.framework(testArgs.framework),
        entireClasspath = Agg.from(testArgs.classpath.map(os.Path(_))),
        testClassfilePath = Agg(os.Path(testArgs.testCp)),
        args = testArgs.arguments,
        DummyTestReporter,
        filter
      )(ctx)

      // Clear interrupted state in case some badly-behaved test suite
      // dirtied the thread-interrupted flag and forgot to clean up. Otherwise
      // that flag causes writing the results to disk to fail
      Thread.interrupted()
      os.write(os.Path(testArgs.outputPath), upickle.default.stream(result))
    } catch {
      case e: Throwable =>
        println(e)
        e.printStackTrace()
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }

  def runTestFramework(
      frameworkInstances: ClassLoader => sbt.testing.Framework,
      entireClasspath: Agg[os.Path],
      testClassfilePath: Agg[os.Path],
      args: Seq[String],
      testReporter: TestReporter
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[mill.testrunner.TestRunner.Result]) = {
    runTestFramework(
      frameworkInstances,
      entireClasspath,
      testClassfilePath,
      args,
      testReporter,
      _ => true
    )
  }

  def runTestFramework(
      frameworkInstances: ClassLoader => sbt.testing.Framework,
      entireClasspath: Agg[os.Path],
      testClassfilePath: Agg[os.Path],
      args: Seq[String],
      testReporter: TestReporter,
      classFilter: Class[_] => Boolean
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[mill.testrunner.TestRunner.Result]) = {
    // Leave the context class loader set and open so that shutdown hooks can access it
    Jvm.inprocess(
      entireClasspath,
      classLoaderOverrideSbtTesting = true,
      isolated = true,
      closeContextClassLoaderWhenDone = false,
      cl => {
        val framework = frameworkInstances(cl)

        val events = mutable.Buffer.empty[Event]

        val doneMessage = {
          val runner = framework.runner(args.toArray, Array[String](), cl)

          val testClasses = discoverTests(cl, framework, testClassfilePath)

          val tasks = runner.tasks(
            for ((cls, fingerprint) <- testClasses.iterator.toArray if classFilter(cls))
              yield new TaskDef(
                cls.getName.stripSuffix("$"),
                fingerprint,
                true,
                Array(new SuiteSelector)
              )
          )

          val taskQueue = tasks.to(mutable.Queue)
          while (taskQueue.nonEmpty) {
            val next = taskQueue
              .dequeue()
              .execute(
                new EventHandler {
                  def handle(event: Event) = {
                    testReporter.logStart(event)
                    events.append(event)
                    testReporter.logFinish(event)
                  }
                },
                Array(new Logger {
                  def debug(msg: String) = ctx.log.outputStream.println(msg)

                  def error(msg: String) = ctx.log.outputStream.println(msg)

                  def ansiCodesSupported() = true

                  def warn(msg: String) = ctx.log.outputStream.println(msg)

                  def trace(t: Throwable) =
                    t.printStackTrace(ctx.log.outputStream)

                  def info(msg: String) = ctx.log.outputStream.println(msg)
                })
              )
            taskQueue.enqueueAll(next)
          }
          runner.done()
        }

        val results = for (e <- events) yield {
          val ex =
            if (e.throwable().isDefined) Some(e.throwable().get) else None
          mill.testrunner.TestRunner.Result(
            e.fullyQualifiedName(),
            e.selector() match {
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
            ex.map(_.getStackTrace.toIndexedSeq)
          )
        }

        (doneMessage, results.toSeq)
      }
    )
  }

  def framework(frameworkName: String)(
      cl: ClassLoader
  ): sbt.testing.Framework = {
    cl.loadClass(frameworkName)
      .getDeclaredConstructor().newInstance()
      .asInstanceOf[sbt.testing.Framework]
  }

  def globFilter(selectors: Seq[String]): Class[_] => Boolean = {
    val filters = selectors.map { str =>
      if (str == "*") (_: String) => true
      else if (str.indexOf('*') == -1) (s: String) => s == str
      else {
        val parts = str.split("\\*", -1)
        parts match {
          case Array("", suffix) => (s: String) => s.endsWith(suffix)
          case Array(prefix, "") => (s: String) => s.startsWith(prefix)
          case _ =>
            val pattern = Pattern.compile(parts.map(Pattern.quote).mkString(".*"))
            (s: String) => pattern.matcher(s).matches()
        }
      }
    }

    if (filters.isEmpty) (_: Class[_]) => true
    else
      (clz: Class[_]) => {
        val name = clz.getName.stripSuffix("$")
        filters.exists(f => f(name))
      }
  }

  case class Result(
      fullyQualifiedName: String,
      selector: String,
      duration: Long,
      status: String,
      exceptionName: Option[String] = None,
      exceptionMsg: Option[String] = None,
      exceptionTrace: Option[Seq[StackTraceElement]] = None
  )

  object Result {
    implicit def resultRW: upickle.default.ReadWriter[Result] =
      upickle.default.macroRW[Result]
  }
}
