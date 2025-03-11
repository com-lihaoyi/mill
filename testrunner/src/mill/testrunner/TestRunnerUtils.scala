package mill.testrunner

import mill.api.{Ctx, Loose, TestReporter, internal}
import os.Path
import sbt.testing._

import java.nio.file.Files
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import scala.collection.mutable
import scala.jdk.CollectionConverters.IteratorHasAsScala

@internal object TestRunnerUtils {

  private type ClassWithFingerprint = (Class[?], Fingerprint)

  def listClassFiles(base: os.Path): geny.Generator[String] = {
    if (os.isDir(base)) {
      os.walk.stream(base).filter(_.ext == "class").map(_.relativeTo(base).toString)
    } else {
      val zip = new ZipInputStream(Files.newInputStream(base.toNIO))
      geny.Generator.selfClosing(
        (
          Iterator.continually(zip.getNextEntry)
            .takeWhile(_ != null)
            .map(_.getName)
            .filter(_.endsWith(".class")),
          () => zip.close()
        )
      )
    }
  }

  def discoverTests(
      cl: ClassLoader,
      framework: Framework,
      classpath: Loose.Agg[os.Path]
  ): Loose.Agg[ClassWithFingerprint] = {

    val fingerprints = framework.fingerprints()

    val testClasses = classpath
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      .filter(os.exists(_))
      .flatMap { base =>
        Loose.Agg.from[ClassWithFingerprint](
          listClassFiles(base).map { path =>
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
            .toSeq
            .flatten
        )
      }
      // I think this is a bug in sbt-junit-interface. AFAICT, JUnit is not
      // meant to pick up non-static inner classes as test suites, and doing
      // so makes the jimfs test suite fail
      //
      // https://stackoverflow.com/a/17468590
      .filter { case (c, f) => !c.isMemberClass && !c.isAnonymousClass }

    testClasses
  }

  def matchFingerprints(
      cl: ClassLoader,
      cls: Class[_],
      fingerprints: Array[Fingerprint],
      isModule: Boolean
  ): Option[ClassWithFingerprint] = {
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

  def getTestTasks(
      framework: Framework,
      args: Seq[String],
      classFilter: Class[_] => Boolean,
      cl: ClassLoader,
      testClassfilePath: Loose.Agg[Path]
  ): (Runner, Array[Task]) = {

    val runner = framework.runner(args.toArray, Array[String](), cl)
    val testClasses = discoverTests(cl, framework, testClassfilePath)

    val tasks = runner.tasks(
      for ((cls, fingerprint) <- testClasses.iterator.toArray if classFilter(cls))
        yield new TaskDef(
          cls.getName.stripSuffix("$"),
          fingerprint,
          false,
          Array(new SuiteSelector)
        )
    )

    (runner, tasks)
  }

  private def executeTasks(
      tasks: Seq[Task],
      testReporter: TestReporter,
      runner: Runner,
      events: ConcurrentLinkedQueue[Event]
  )(implicit ctx: Ctx.Log with Ctx.Home): Unit = {
    val taskQueue = tasks.to(mutable.Queue)
    while (taskQueue.nonEmpty) {
      val next = taskQueue.dequeue().execute(
        new EventHandler {
          def handle(event: Event) = {
            testReporter.logStart(event)
            events.add(event)
            testReporter.logFinish(event)
          }
        },
        Array(new Logger {
          def debug(msg: String) = ctx.log.outputStream.println(msg)
          def error(msg: String) = ctx.log.outputStream.println(msg)
          def ansiCodesSupported() = true
          def warn(msg: String) = ctx.log.outputStream.println(msg)
          def trace(t: Throwable) = t.printStackTrace(ctx.log.outputStream)
          def info(msg: String) = ctx.log.outputStream.println(msg)
        })
      )

      taskQueue.enqueueAll(next)
    }
  }

  def parseRunTaskResults(events: Iterator[Event]): Iterator[TestResult] = {
    for (e <- events) yield {
      val ex =
        if (e.throwable().isDefined) Some(e.throwable().get) else None
      mill.testrunner.TestResult(
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
  }

  private def handleRunnerDone(
      runner: Runner,
      events: ConcurrentLinkedQueue[Event]
  ): (String, Iterator[TestResult]) = {
    val doneMessage = runner.done()
    if (doneMessage != null && doneMessage.nonEmpty) {
      if (doneMessage.endsWith("\n"))
        println(doneMessage.stripSuffix("\n"))
      else
        println(doneMessage)
    }

    val results = parseRunTaskResults(events.iterator().asScala)

    (doneMessage, results)
  }

  def runTasks(tasks: Seq[Task], testReporter: TestReporter, runner: Runner)(implicit
      ctx: Ctx.Log with Ctx.Home
  ): (String, Iterator[TestResult]) = {
    // Capture this value outside of the task event handler so it
    // isn't affected by a test framework's stream redirects
    val events = new ConcurrentLinkedQueue[Event]()
    executeTasks(tasks, testReporter, runner, events)
    handleRunnerDone(runner, events)
  }

  def runTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Loose.Agg[Path],
      args: Seq[String],
      classFilter: Class[_] => Boolean,
      cl: ClassLoader,
      testReporter: TestReporter
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)

    val (runner, tasks) = getTestTasks(framework, args, classFilter, cl, testClassfilePath)

    val (doneMessage, results) = runTasks(tasks, testReporter, runner)

    (doneMessage, results.toSeq)
  }

  def stealTasks(
      testClasses: Loose.Agg[ClassWithFingerprint],
      testReporter: TestReporter,
      runner: Runner,
      stealFolder: os.Path,
      testClassesFolder: os.Path
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Iterator[TestResult]) = {
    // Capture this value outside of the task event handler so it
    // isn't affected by a test framework's stream redirects
    val events = new ConcurrentLinkedQueue[Event]()
    val globSelectorCache = testClasses.view
      .map { case (cls, fingerprint) => cls.getName.stripSuffix("$") -> (cls, fingerprint) }
      .toMap

    // append only log, used to communicate with parent about what test is being stolen
    // so that the parent can log the stolen test's name to its logger
    val stealLog = stealFolder / os.up / s"${stealFolder.last}.log"
    for (file <- os.list(testClassesFolder)) {
      val testClassName = file.last
      val stolenFile = stealFolder / testClassName

      // we can check for existence of stolenFile first, but it'll require another os call.
      // it just better to let this call failed in that case.
      val stole = scala.util.Try(os.move(file, stolenFile, atomicMove = true)).isSuccess

      if (stole) {
        os.write.append(stealLog, s"$testClassName\n")
        val taskDefs = globSelectorCache
          .get(testClassName)
          .map { case (cls, fingerprint) =>
            val clsName = cls.getName.stripSuffix("$")
            new TaskDef(clsName, fingerprint, false, Array(new SuiteSelector))
          }

        val tasks = runner.tasks(taskDefs.toArray)
        executeTasks(tasks, testReporter, runner, events)
      }
    }

    handleRunnerDone(runner, events)
  }

  def stealTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Loose.Agg[Path],
      args: Seq[String],
      testClassesFolder: os.Path,
      stealFolder: os.Path,
      cl: ClassLoader,
      testReporter: TestReporter
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)

    val runner = framework.runner(args.toArray, Array[String](), cl)

    val testClasses = discoverTests(cl, framework, testClassfilePath)

    val (doneMessage, results) =
      stealTasks(testClasses, testReporter, runner, stealFolder, testClassesFolder)

    (doneMessage, results.toSeq)
  }

  def getTestTasks0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Loose.Agg[Path],
      args: Seq[String],
      classFilter: Class[_] => Boolean,
      cl: ClassLoader
  ): Array[String] = {
    val framework = frameworkInstances(cl)
    val (runner, tasks) = getTestTasks(framework, args, classFilter, cl, testClassfilePath)
    tasks.map(_.taskDef().fullyQualifiedName())
  }

  def globFilter(selectors: Seq[String]): String => Boolean = {
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

    if (filters.isEmpty) _ => true
    else { className =>
      val name = className.stripSuffix("$")
      filters.exists(f => f(name))
    }
  }
}
