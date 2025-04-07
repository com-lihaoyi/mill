package mill.testrunner

import mill.runner.api.TestReporter
import mill.api.internal
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
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import scala.math.Ordering.Implicits._

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
      classpath: Seq[os.Path]
  ): Seq[ClassWithFingerprint] = {

    val fingerprints = framework.fingerprints()

    val testClasses = classpath
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      .filter(os.exists(_))
      .flatMap { base =>
        Seq.from[ClassWithFingerprint](
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
      cls: Class[?],
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
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      testClassfilePath: Seq[Path]
  ): (Runner, Array[Array[Task]]) = {

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

    def nameOpt(t: Task) = Option(t.taskDef()).map(_.fullyQualifiedName())
    val groupedTasks = tasks
      .groupBy(nameOpt)
      .values
      .toArray
      .sortBy(_.headOption.map(nameOpt))

    (runner, groupedTasks)
  }

  private def executeTasks(
      tasks: Seq[Task],
      testReporter: TestReporter,
      events: ConcurrentLinkedQueue[Event],
      systemOut: PrintStream
  ): Boolean = {
    val taskStatus = new AtomicBoolean(true)
    val taskQueue = tasks.to(mutable.Queue)
    while (taskQueue.nonEmpty) {
      val next = taskQueue.dequeue().execute(
        new EventHandler {
          def handle(event: Event) = {
            testReporter.logStart(event)
            events.add(event)
            event.status match {
              case Status.Error => taskStatus.set(false)
              case Status.Failure => taskStatus.set(false)
              case _ => () // consider success as a default
            }
            testReporter.logFinish(event)
          }
        },
        Array(new Logger {
          private val logDebug = testReporter.logLevel <= TestReporter.LogLevel.Debug
          private val logInfo = testReporter.logLevel <= TestReporter.LogLevel.Info
          private val logWarn = testReporter.logLevel <= TestReporter.LogLevel.Warn
          private val logError = testReporter.logLevel <= TestReporter.LogLevel.Error

          def debug(msg: String) = if (logDebug) systemOut.println(msg)
          def error(msg: String) = if (logError) systemOut.println(msg)
          def ansiCodesSupported() = true
          def warn(msg: String) = if (logWarn) systemOut.println(msg)
          def trace(t: Throwable) = t.printStackTrace(systemOut)
          def info(msg: String) = if (logInfo) systemOut.println(msg)
        })
      )

      taskQueue.enqueueAll(next)
    }
    taskStatus.get()
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

  def runTasks(
      tasksSeq: Seq[Seq[Task]],
      testReporter: TestReporter,
      runner: Runner,
      resultPathOpt: Option[os.Path]
  ): (String, Iterator[TestResult]) = {
    // Capture this value outside of the task event handler so it
    // isn't affected by a test framework's stream redirects
    val systemOut = System.out
    val events = new ConcurrentLinkedQueue[Event]()

    var successCounter = 0L
    var failureCounter = 0L

    val resultLog: () => Unit = resultPathOpt match {
      case Some(resultPath) =>
        () => os.write.over(resultPath, upickle.default.write((successCounter, failureCounter)))
      case None => () =>
          systemOut.println(s"Test result: ${successCounter + failureCounter} completed${
              if failureCounter > 0 then s", ${failureCounter} failures." else "."
            }")
    }

    tasksSeq.foreach { tasks =>
      val taskResult = executeTasks(tasks, testReporter, events, systemOut)
      if taskResult then successCounter += 1 else failureCounter += 1
      resultLog()
    }

    handleRunnerDone(runner, events)
  }

  def runTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Seq[Path],
      args: Seq[String],
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      testReporter: TestReporter,
      resultPathOpt: Option[os.Path] = None
  ): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)

    val (runner, tasksArr) = getTestTasks(framework, args, classFilter, cl, testClassfilePath)

    val (doneMessage, results) =
      runTasks(tasksArr.view.map(_.toSeq).toSeq, testReporter, runner, resultPathOpt)

    (doneMessage, results.toSeq)
  }

  def runTasksFromQueue(
      startingTestClass: Option[String],
      testClasses: Seq[ClassWithFingerprint],
      testReporter: TestReporter,
      runner: Runner,
      claimFolder: os.Path,
      testClassQueueFolder: os.Path,
      resultPath: os.Path
  ): (String, Iterator[TestResult]) = {
    // Capture this value outside of the task event handler so it
    // isn't affected by a test framework's stream redirects
    val systemOut = System.out
    val events = new ConcurrentLinkedQueue[Event]()
    val globSelectorCache = testClasses.view
      .map { case (cls, fingerprint) => cls.getName.stripSuffix("$") -> (cls, fingerprint) }
      .toMap
    var successCounter = 0L
    var failureCounter = 0L

    def runClaimedTestClass(testClassName: String) = {

      if (testReporter.logLevel <= TestReporter.LogLevel.Debug)
        System.err.println(s"Running Test Class $testClassName")
      val taskDefs = globSelectorCache
        .get(testClassName)
        .map { case (cls, fingerprint) =>
          val clsName = cls.getName.stripSuffix("$")
          new TaskDef(clsName, fingerprint, false, Array(new SuiteSelector))
        }

      val tasks = runner.tasks(taskDefs.toArray)
      val taskResult = executeTasks(tasks, testReporter, events, systemOut)
      if taskResult then successCounter += 1 else failureCounter += 1
      os.write.over(resultPath, upickle.default.write((successCounter, failureCounter)))
    }

    startingTestClass.foreach { testClass =>
      os.write.append(claimFolder / os.up / s"${claimFolder.last}.log", s"$testClass\n")
      runClaimedTestClass(testClass)
    }

    for (file <- os.list(testClassQueueFolder)) {
      for (claimedTestClass <- claimFile(file, claimFolder)) runClaimedTestClass(claimedTestClass)
    }

    handleRunnerDone(runner, events)
  }

  def claimFile(file: os.Path, claimFolder: os.Path): Option[String] = {
    Option.when(
      os.exists(file) &&
        scala.util.Try(os.move(file, claimFolder / file.last, atomicMove = true)).isSuccess
    ) {
      // append only log, used to communicate with parent about what test is being claimed
      // so that the parent can log the claimed test's name to its logger
      val claimLog = claimFolder / os.up / s"${claimFolder.last}.log"
      os.write.append(claimLog, s"${file.last}\n")
      file.last
    }
  }

  def queueTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Seq[Path],
      args: Seq[String],
      startingTestClass: Option[String],
      testClassQueueFolder: os.Path,
      claimFolder: os.Path,
      cl: ClassLoader,
      testReporter: TestReporter,
      resultPath: os.Path
  ): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)

    val runner = framework.runner(args.toArray, Array[String](), cl)

    val testClasses = discoverTests(cl, framework, testClassfilePath)

    val (doneMessage, results) = runTasksFromQueue(
      startingTestClass,
      testClasses,
      testReporter,
      runner,
      claimFolder,
      testClassQueueFolder,
      resultPath
    )

    (doneMessage, results.toSeq)
  }

  def getTestTasks0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Seq[Path],
      args: Seq[String],
      classFilter: Class[?] => Boolean,
      cl: ClassLoader
  ): Array[String] = {
    val framework = frameworkInstances(cl)
    val (runner, tasksArr) = getTestTasks(framework, args, classFilter, cl, testClassfilePath)
    tasksArr.flatten.map(_.taskDef().fullyQualifiedName())
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
