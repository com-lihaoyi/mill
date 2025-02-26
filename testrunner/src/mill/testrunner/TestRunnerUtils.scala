package mill.testrunner

import mill.api.{Ctx, TestReporter, internal}
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
import scala.concurrent.Promise
import java.util.concurrent.Executors
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try
import java.util.concurrent.ThreadFactory

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

  def testClassesGenerator(classpath: Seq[os.Path]): geny.Generator[String] =
    geny.Generator.from(classpath).filter(os.exists(_)).flatMap { base => listClassFiles(base) }

  def discoverTestsFromClasspathString(
      cl: ClassLoader,
      framework: Framework,
      classpath: Seq[String]
  ): Seq[(Class[?], Fingerprint)] = {
    val fingerprints = framework.fingerprints()

    val testClasses = classpath.map { path =>
      val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
      val publicConstructorCount = cls.getConstructors.count(c => Modifier.isPublic(c.getModifiers))

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
      .flatten
      // I think this is a bug in sbt-junit-interface. AFAICT, JUnit is not
      // meant to pick up non-static inner classes as test suites, and doing
      // so makes the jimfs test suite fail
      //
      // https://stackoverflow.com/a/17468590
      .filter { case (c, f) => !c.isMemberClass && !c.isAnonymousClass }

    testClasses
  }

  def discoverTests(
      cl: ClassLoader,
      framework: Framework,
      classpath: Seq[os.Path]
  ): Seq[(Class[?], Fingerprint)] = {

    val classpathString = testClassesGenerator(classpath)

    discoverTestsFromClasspathString(
      cl,
      framework,
      classpathString.toSeq
    )
  }

  def matchFingerprints(
      cl: ClassLoader,
      cls: Class[?],
      fingerprints: Array[Fingerprint],
      isModule: Boolean
  ): Option[(Class[?], Fingerprint)] = {
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

  def getTestTaskDefs(
      framework: Framework,
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      testClassfilePath: Seq[Path]
  ): Array[TaskDef] = {
    val testClasses = discoverTests(cl, framework, testClassfilePath)
    for ((cls, fingerprint) <- testClasses.iterator.toArray if classFilter(cls))
      yield new TaskDef(
        cls.getName.stripSuffix("$"),
        fingerprint,
        false,
        Array(new SuiteSelector)
      )
  }

  def getTestTasks(
      framework: Framework,
      args: Seq[String],
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      testClassfilePath: Seq[Path]
  ): (Runner, Array[Task]) = {

    val runner = framework.runner(args.toArray, Array[String](), cl)
    val taskDefs = getTestTaskDefs(framework, classFilter, cl, testClassfilePath)

    val tasks = runner.tasks(taskDefs)

    (runner, tasks)
  }

  private def extractTestResult(
      e: Event
  ): TestResult = {
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

  def runTestTaskInCluster(
      processIndex: Int,
      framework: Framework,
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      startTestClasses: Seq[String],
      testReporter: TestReporter,
      runner: Runner,
      clusterSignals: TestClusterSignals
  )(implicit ctx: Ctx.Log): (String, Iterator[TestResult]) = {

    val events = new ConcurrentLinkedQueue[Event]()
    val testClassesQueue = new ConcurrentLinkedQueue[String]()

    startTestClasses.foreach(testClassesQueue.offer)

    val workCounter = new LongAdder()
    val isRunning = new AtomicBoolean(false)
    val promise = Promise[Unit]()

    val executor = Executors.newFixedThreadPool(
      2,
      new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r)
          t.setDaemon(true)
          t.setPriority(Thread.MIN_PRIORITY)
          t
        }
      }
    )

    def readTestClassesFromProcess(processId: Int): Seq[String] = {
      clusterSignals.tryLockStealingFile(processId) match {
        case None =>
          // cannot get the lock
          Seq.empty
        case Some(lock) =>
          // got the lock, try take test classes from the file
          try {
            val file = clusterSignals.getStealingFile(processId)
            val results = os.read.lines.stream(file).toSeq
            os.write.over(file, Array.emptyByteArray)
            results
          } finally {
            lock.release()
          }
      }
    }

    def writeProcessTestClasses(processId: Int, testClasses: Seq[String]): Unit = {
      val lock = clusterSignals.lockStealingFile(processId)
      try {
        val file = clusterSignals.getStealingFile(processId)
        testClasses.foreach(line => os.write.append(file, s"$line\n"))
      } finally {
        lock.release()
      }
    }

    val clusterHandler = new Runnable {
      private var stealAttemptDenied = 32

      private def trySignalStealWhileRunning(): Boolean = {
        // we should steal more test, but first, try take back some test that we've prepared for other stealers
        val inTestClasses = readTestClassesFromProcess(processIndex)
        if (inTestClasses.nonEmpty) {
          // we took back some test classes
          inTestClasses.foreach(testClassesQueue.offer)
          true
        } else {
          // signal cluster server for steal attempt
          clusterSignals.writeClusterState(
            processIndex,
            TestClusterSignals.ClusterState.RequestingStealPermission
          )
          false
        }
      }

      private def toNextStealAttempt(): Unit = {
        if (stealAttemptDenied > 0) {
          stealAttemptDenied -= 1
          clusterSignals.writeClusterState(
            processIndex,
            TestClusterSignals.ClusterState.RequestingStealPermission
          )
        } else {
          clusterSignals.writeClusterState(processIndex, TestClusterSignals.ClusterState.Stop)
        }
      }

      private def steal(victimProcessId: Int): Unit = {
        while (!promise.isCompleted) {
          if (
            clusterSignals.getClusterState(victimProcessId) == TestClusterSignals.ClusterState.Stop
          ) {
            toNextStealAttempt()
            return
          } else {
            clusterSignals.getStealerState(victimProcessId) match {
              case TestClusterSignals.StealerState.Empty =>
                // victim is not being stolen, we can steal
                clusterSignals.writeStealerState(
                  victimProcessId,
                  TestClusterSignals.StealerState.RequestStealAttempt(processIndex)
                )
              case TestClusterSignals.StealerState.RequestStealAttempt(stealerProcessId) =>
                if (stealerProcessId != processIndex) {
                  toNextStealAttempt()
                  return
                }
              case TestClusterSignals.StealerState.StealAttemptApproved(stealerProcessId) =>
                if (stealerProcessId != processIndex) {
                  toNextStealAttempt()
                } else {
                  val inTestClasses = readTestClassesFromProcess(victimProcessId)
                  if (inTestClasses.nonEmpty) {
                    // stole some tests
                    inTestClasses.foreach(testClassesQueue.offer)
                    clusterSignals.writeStealerState(
                      victimProcessId,
                      TestClusterSignals.StealerState.StealerAcknowledged
                    )
                    clusterSignals.writeClusterState(
                      processIndex,
                      TestClusterSignals.ClusterState.Running
                    )
                    stealAttemptDenied = 32
                  } else {
                    // signal cluster server for another steal attempt
                    clusterSignals.writeStealerState(
                      victimProcessId,
                      TestClusterSignals.StealerState.StealerAcknowledged
                    )
                    toNextStealAttempt()
                  }
                }
                return
              case TestClusterSignals.StealerState.StealAttemptDenied(stealerProcessId) =>
                // victim denied the steal attempt
                if (stealerProcessId == processIndex) {
                  clusterSignals.writeStealerState(
                    victimProcessId,
                    TestClusterSignals.StealerState.StealerAcknowledged
                  )
                }
                toNextStealAttempt()
                return
              case TestClusterSignals.StealerState.StealerAcknowledged => ()
              case TestClusterSignals.StealerState.Unrecognize => ()
            }
          }
          Thread.`yield`()
        }
      }

      override def run() = {
        while (!promise.isCompleted) {
          clusterSignals.getClusterState(processIndex) match {
            case TestClusterSignals.ClusterState.Running =>
              val running = isRunning.get()
              val testClassesEmpty = testClassesQueue.isEmpty()
              if (!running && testClassesEmpty) {
                val _ = trySignalStealWhileRunning()
              } else {
                val lastProcessCount = workCounter.sum()
                Thread.sleep(10)
                val currentProcessCount = workCounter.sum()
                if (!running && testClassesEmpty) {
                  val _ = trySignalStealWhileRunning()
                } else if (
                  lastProcessCount == currentProcessCount && running && !testClassesEmpty
                ) {
                  // we're blocking, notify cluster server
                  clusterSignals.writeClusterState(
                    processIndex,
                    TestClusterSignals.ClusterState.Blocking
                  )
                }
              }
            case TestClusterSignals.ClusterState.Blocking =>
              val running = isRunning.get()
              val testClassesEmpty = testClassesQueue.isEmpty()
              if (!running && testClassesEmpty) {
                if (trySignalStealWhileRunning()) clusterSignals.writeClusterState(
                  processIndex,
                  TestClusterSignals.ClusterState.Running
                )
              } else {
                val lastProcessCount = workCounter.sum()
                Thread.sleep(10)
                val currentProcessCount = workCounter.sum()
                if (!running && testClassesEmpty) {
                  if (trySignalStealWhileRunning()) clusterSignals.writeClusterState(
                    processIndex,
                    TestClusterSignals.ClusterState.Running
                  )
                } else if (
                  lastProcessCount == currentProcessCount && running && !testClassesEmpty
                ) {
                  // still blocking,
                  ()
                } else {
                  clusterSignals.writeClusterState(
                    processIndex,
                    TestClusterSignals.ClusterState.Running
                  )
                }
              }
            case TestClusterSignals.ClusterState.Stealing(victimProcessId) => steal(victimProcessId)
            case TestClusterSignals.ClusterState.RequestingStealPermission => ()
            case TestClusterSignals.ClusterState.StealPermissionApproved(victimProcessId) =>
              clusterSignals.writeClusterState(
                processIndex,
                TestClusterSignals.ClusterState.Stealing(victimProcessId)
              )
            case TestClusterSignals.ClusterState.StealPermissionDenied =>
              // cluster denied the steal permission
              toNextStealAttempt()
            case TestClusterSignals.ClusterState.Stop => promise.tryComplete(Try(()))
            case TestClusterSignals.ClusterState.Unrecognize =>
              // fix corrupted signal
              clusterSignals.writeClusterState(
                processIndex,
                TestClusterSignals.ClusterState.Running
              )
          }
          Thread.`yield`()
        }
      }
    }

    val stealerHandler = new Runnable {
      override def run() = {
        while (!promise.isCompleted) {
          clusterSignals.getStealerState(processIndex) match {
            case TestClusterSignals.StealerState.Empty => ()
            case TestClusterSignals.StealerState.RequestStealAttempt(stealerProcessId) =>
              // prepare task for steal attempt
              val stealingTestClasses = mutable.ArrayBuffer.empty[String]
              var count = (testClassesQueue.size() + 1) / 2
              while (count > 0) {
                val stolenTestClass = testClassesQueue.poll()
                if (stolenTestClass ne null) stealingTestClasses.append(stolenTestClass)
                count -= 1
              }
              if (stealingTestClasses.nonEmpty) {
                writeProcessTestClasses(processIndex, stealingTestClasses.toSeq)
                clusterSignals.writeStealerState(
                  processIndex,
                  TestClusterSignals.StealerState.StealAttemptApproved(stealerProcessId)
                )
              } else {
                clusterSignals.writeStealerState(
                  processIndex,
                  TestClusterSignals.StealerState.StealAttemptDenied(stealerProcessId)
                )
              }
            case TestClusterSignals.StealerState.StealAttemptApproved(stealerProcessId) => ()
            case TestClusterSignals.StealerState.StealAttemptDenied(stealerProcessId) => ()
            case TestClusterSignals.StealerState.StealerAcknowledged =>
              clusterSignals.writeStealerState(processIndex, TestClusterSignals.StealerState.Empty)
            case TestClusterSignals.StealerState.Unrecognize =>
              clusterSignals.writeStealerState(processIndex, TestClusterSignals.StealerState.Empty)
          }
          Thread.`yield`()
        }
      }
    }

    executor.execute(stealerHandler)
    executor.execute(clusterHandler)

    var task: Task = null
    val tasks = mutable.Queue.empty[Task]

    while (!promise.isCompleted) {
      if ((task eq null) && tasks.nonEmpty) {
        task = tasks.dequeue()
      }
      if (task eq null) {
        val curTestClass = testClassesQueue.poll()
        if (curTestClass ne null) {
          isRunning.set(true)
          val testClasses = discoverTestsFromClasspathString(cl, framework, Seq(curTestClass))
          val taskDefs =
            for ((cls, fingerprint) <- testClasses.iterator.toArray if classFilter(cls))
              yield new TaskDef(
                cls.getName.stripSuffix("$"),
                fingerprint,
                false,
                Array(new SuiteSelector)
              )
          workCounter.increment()
          val newTasks = runner.tasks(taskDefs)
          workCounter.increment()
          if (newTasks.nonEmpty) {
            task = newTasks.head
            newTasks.tail.foreach(tasks.enqueue)
          }
          isRunning.set(false)
        }
      }
      if (task ne null) {
        isRunning.set(true)
        workCounter.increment()
        val nextTasks = task.execute(
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
        nextTasks.foreach(tasks.enqueue)
        task = null
        isRunning.set(false)
      }
    }

    val doneMessage = runner.done()

    if (doneMessage != null && doneMessage.nonEmpty) {
      if (doneMessage.endsWith("\n"))
        ctx.log.outputStream.print(doneMessage)
      else
        ctx.log.outputStream.println(doneMessage)
    }

    val results = for (e <- events.iterator().asScala) yield extractTestResult(e)

    (doneMessage, results)
  }

  def runTestFrameworkInCluster0(
      processIndex: Int,
      frameworkInstances: ClassLoader => Framework,
      startTestClasses: Seq[Path],
      args: Seq[String],
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      testReporter: TestReporter,
      clusterSignals: TestClusterSignals
  )(implicit ctx: Ctx.Log): (String, Seq[TestResult]) = {
    val framework = frameworkInstances(cl)
    val runner = framework.runner(args.toArray, Array[String](), cl)

    val (doneMessage, results) = runTestTaskInCluster(
      processIndex,
      framework,
      classFilter,
      cl,
      testClassesGenerator(startTestClasses).toSeq,
      testReporter,
      runner,
      clusterSignals
    )

    (doneMessage, results.toSeq)
  }

  def runTasks(tasks: Seq[Task], testReporter: TestReporter, runner: Runner)(implicit
      ctx: Ctx.Log
  ): (String, Iterator[TestResult]) = {
    val events = new ConcurrentLinkedQueue[Event]()
    val doneMessage = {

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
      runner.done()
    }

    if (doneMessage != null && doneMessage.nonEmpty) {
      if (doneMessage.endsWith("\n"))
        ctx.log.outputStream.print(doneMessage)
      else
        ctx.log.outputStream.println(doneMessage)
    }

    val results = for (e <- events.iterator().asScala) yield extractTestResult(e)

    (doneMessage, results)
  }

  def runTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Seq[Path],
      args: Seq[String],
      classFilter: Class[?] => Boolean,
      cl: ClassLoader,
      testReporter: TestReporter
  )(implicit ctx: Ctx.Log): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)

    val (runner, tasks) = getTestTasks(framework, args, classFilter, cl, testClassfilePath)

    val (doneMessage, results) = runTasks(tasks.toSeq, testReporter, runner)

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
