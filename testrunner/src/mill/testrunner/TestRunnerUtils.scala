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
import scala.util.Random
import java.io.PrintStream

@internal object TestRunnerUtils {

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
  ): Loose.Agg[(Class[_], Fingerprint)] = {

    val fingerprints = framework.fingerprints()

    val testClasses = classpath
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      .filter(os.exists(_))
      .flatMap { base =>
        Loose.Agg.from[(Class[_], Fingerprint)](
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
    events: ConcurrentLinkedQueue[Event],
    systemOut: PrintStream
  ): Unit = {
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
          def debug(msg: String) = systemOut.println(msg)
          def error(msg: String) = systemOut.println(msg)
          def ansiCodesSupported() = true
          def warn(msg: String) = systemOut.println(msg)
          def trace(t: Throwable) = t.printStackTrace(systemOut)
          def info(msg: String) = systemOut.println(msg)
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

  def runTasks(tasks: Seq[Task], testReporter: TestReporter, runner: Runner): (String, Iterator[TestResult]) = {
    // Capture this value outside of the task event handler so it
    // isn't affected by a test framework's stream redirects
    val systemOut = System.out
    val events = new ConcurrentLinkedQueue[Event]()
    val doneMessage = {
      executeTasks(tasks, testReporter, runner, events, systemOut)
      runner.done()
    }

    if (doneMessage != null && doneMessage.nonEmpty) {
      if (doneMessage.endsWith("\n"))
        println(doneMessage.stripSuffix("\n"))
      else
        println(doneMessage)
    }

    val results = parseRunTaskResults(events.iterator().asScala)

    (doneMessage, results)
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

  private def stealTaskFromSelectorFolder(
    testClasses: Seq[(Class[?], Fingerprint)],
    runner: Runner,
    stealFolder: os.Path,
    selectorFolder: os.Path
  ): Array[Task] = {
    while (true) {
      val files = os.list(selectorFolder)
      if (files.nonEmpty) {
        val offset = Random.nextInt(files.size)
        val stole = try {
          os.move(
            files(offset),
            stealFolder / s"selector-stolen",
            atomicMove = true
          )
          true
        } catch {
          case e: Exception => false
        }
        if (stole) {
          val selectors = os.read.lines(stealFolder / s"selector-stolen")
          val classFilter = TestRunnerUtils.globFilter(selectors)
          val tasks = runner.tasks(
            for ((cls, fingerprint) <- testClasses.iterator.toArray if classFilter(cls.getName))
              yield new TaskDef(
                cls.getName.stripSuffix("$"),
                fingerprint,
                false,
                Array(new SuiteSelector)
              )
          )
          os.remove(stealFolder / s"selector-stolen")
          return tasks
        }
      } else {
        return Array.empty[Task]
      }
    }

    // Compiler doesn't know that we returned from above, add this as a sanity check and assertion
    ???
  }

  def stealTasks(
    testClasses: Seq[(Class[?], Fingerprint)],
    testReporter: TestReporter,
    runner: Runner,
    stealFolder: os.Path,
    selectorFolder: os.Path
  ): (String, Iterator[TestResult]) = {
    // Capture this value outside of the task event handler so it
    // isn't affected by a test framework's stream redirects
    val systemOut = System.out
    val events = new ConcurrentLinkedQueue[Event]()
    val doneMessage = {

      while ({
        val tasks = stealTaskFromSelectorFolder(testClasses, runner, stealFolder, selectorFolder)
        if (tasks.nonEmpty) {
          executeTasks(tasks, testReporter, runner, events, systemOut)
          true
        } else {
          false
        }
      })()

      runner.done()
    }

    if (doneMessage != null && doneMessage.nonEmpty) {
      if (doneMessage.endsWith("\n"))
        println(doneMessage.stripSuffix("\n"))
      else
        println(doneMessage)
    }

    val results = parseRunTaskResults(events.iterator().asScala)

    (doneMessage, results)
  }

  def stealTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Seq[Path],
      args: Seq[String],
      selectorFolder: os.Path,
      stealFolder: os.Path,
      cl: ClassLoader,
      testReporter: TestReporter
  ): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)
    
    val runner = framework.runner(args.toArray, Array[String](), cl)

    val testClasses = discoverTests(cl, framework, testClassfilePath)

    val (doneMessage, results) = stealTasks(testClasses, testReporter, runner, stealFolder, selectorFolder)

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
