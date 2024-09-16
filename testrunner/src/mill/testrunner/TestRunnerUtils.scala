package mill.testrunner

import mill.api.{Ctx, Loose, TestReporter, internal}
import os.Path
import sbt.testing._

import java.io.FileInputStream
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import scala.collection.mutable
import scala.jdk.CollectionConverters.IteratorHasAsScala

@internal object TestRunnerUtils {

  def listClassFiles(base: os.Path): geny.Generator[String] = {
    if (os.isDir(base)) {
      os.walk.stream(base).filter(_.ext == "class").map(_.relativeTo(base).toString)
    } else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
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

  def runTestFramework0(
      frameworkInstances: ClassLoader => Framework,
      testClassfilePath: Loose.Agg[Path],
      args: Seq[String],
      classFilter: Class[_] => Boolean,
      cl: ClassLoader,
      testReporter: TestReporter
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[TestResult]) = {

    val framework = frameworkInstances(cl)
    val events = new ConcurrentLinkedQueue[Event]()

    val doneMessage = {
      val runner = framework.runner(args.toArray, Array[String](), cl)
      val testClasses = discoverTests(cl, framework, testClassfilePath)
        // I think this is a bug in sbt-junit-interface. AFAICT, JUnit is not
        // meant to pick up non-static inner classes as test suites, and doing
        // so makes the jimfs test suite fail
        //
        // https://stackoverflow.com/a/17468590
        .filter { case (c, f) => !c.isMemberClass }

      val tasks = runner.tasks(
        for ((cls, fingerprint) <- testClasses.iterator.toArray if classFilter(cls))
          yield new TaskDef(
            cls.getName.stripSuffix("$"),
            fingerprint,
            false,
            Array(new SuiteSelector)
          )
      )

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

    val results = for (e <- events.iterator().asScala) yield {
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

    (doneMessage, results.toSeq)
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
}
