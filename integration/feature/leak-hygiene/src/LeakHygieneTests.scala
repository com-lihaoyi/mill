package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.testkit.IntegrationTester
import scala.collection.SortedMap
import utest._

/**
 * Run through some common scenarios on a simple Mill build to ensure we don't leak
 * classloaders or threads.
 *
 * Runs through most scenarios twice to make sure the number of open classloaders
 * doesn't increase when nothing has changed.
 */
object LeakHygieneTests extends UtestIntegrationTestSuite {
  def checkClassloaders(tester: IntegrationTester)(kvs: (String, Int)*) = {
    val res = tester.eval(("show", "countClassLoaders"), check = true)

    val read = upickle.default.read[SortedMap[String, Int]](res.out)
    val expected = SortedMap(kvs*)

    assert(read == expected)
  }

  def checkThreads(tester: IntegrationTester)(expected: utest.framework.GoldenFix.Span[Seq[String]]) = {
    val out = tester.eval(("show", "countThreads")).out
    val read = upickle.default.read[Seq[String]](out)
    // Filter out threads from the thread pool that runs tasks
    // 'countThreads' marks the thread that runs it with a '!' prefix
    val taskPoolPrefixOpt = read
      .find(_.startsWith("!execution-contexts-threadpool-"))
      .map(_.stripPrefix("!").split("-thread-").apply(0) + "-thread-")

    val filtered = read
      .filter {
        case s"coursier-pool-$_" => false
        case s"scala-execution-context-$_" => false
        case other =>
          taskPoolPrefixOpt.forall { taskPoolPrefix =>
            !other.startsWith(taskPoolPrefix) &&
            !other.startsWith("!" + taskPoolPrefix)
          }
      }
      .map {
        // Timers have incrementing IDs, but we don't care what
        // the ID is as long as it is a timer thread.
        case s"Timer-$_" => "Timer"
        case s => s
      }
      .sorted

    assertGoldenLiteral(filtered, expected)
  }

  val tests: Tests = Tests {
    test - integrationTest { tester =>
      if (daemonMode) {
        checkClassloaders(tester)(
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.javalib.JvmWorkerModule#worker cl" -> 1,
          "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 1
        )
        checkThreads(tester)(
          Seq(
          "HandleRunThread",
          "JsonArrayLogger mill-chrome-profile.json",
          "JsonArrayLogger mill-profile.json",
          "MillServerActionRunner",
          "MillServerTimeouThread",
          "Process ID Checker Thread",
          "FileToStreamTailerThread",
          "FileToStreamTailerThread",
          "main",
          "prompt-logger-stream-pumper-thread",
          "proxyInputStreamThroughPumper"
          )
        )

        // Exercise clean compile all
        for (i <- Range(0, 2)) {
          tester.eval(("show", "clean"))
          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerManager" -> 1,
            "mill.javalib.JvmWorkerModule#worker cl" -> 2,
            "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 2
          )
          checkThreads(tester)(
            Seq(
            "HandleRunThread",
            "JsonArrayLogger mill-chrome-profile.json",
            "JsonArrayLogger mill-profile.json",
            "MillServerActionRunner",
            "MillServerTimeoutThread",
            "Process ID Checker Thread",
            "FileToStreamTailerThread",
            "FileToStreamTailerThread",
            "Timer",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
            )
          )

        }

        // Exercise no-op compile all
        for (i <- Range(0, 2)) {
          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerManager" -> 1,
            "mill.javalib.JvmWorkerModule#worker cl" -> 2,
            "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 2
          )
          checkThreads(tester)(
            Seq(
            "HandleRunThread",
            "JsonArrayLogger mill-chrome-profile.json",
            "JsonArrayLogger mill-profile.json",
            "MillServerActionRunner",
            "MillServerTimeoutThead",
            "Process ID Checker Thread",
            "FileToStreamTailerThread",
            "FileToStreamTailerThread",
            "Timer",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
            )
          )
        }

        // Exercise post-shutdown

        tester.eval(("shutdown"), check = true)
        checkClassloaders(tester)(
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.javalib.JvmWorkerModule#worker cl" -> 1
        )
        checkThreads(tester)(
          Seq(
          "HandleRunThread",
          "JsonArrayLogger mill-chrome-profile.json",
          "JsonArrayLogger mill-profile.json",
          "MillServerActionRunner",
          "MillServerTimeoutThread",
          "Process ID Checker Thread",
          "FileToStreamTailerThread",
          "FileToStreamTailerThread",
          "main",
          "prompt-logger-stream-pumper-thread",
          "proxyInputStreamThroughPumper"
          )
        )

        // Exercise clean compile all post-shutdown
        for (i <- Range(0, 2)) {
          tester.eval(("show", "clean"))
          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerManager" -> 1,
            "mill.javalib.JvmWorkerModule#worker cl" -> 2,
            "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 1
          )
          checkThreads(tester)(
            Seq(
            "HandleRunThread",
            "JsonArrayLogger mill-chrome-profile.json",
            "JsonArrayLogger mill-profile.json",
            "MillServerActionRunner",
            "MillServerTimeoutThread",
            "Process ID Checker Thread",
            "FileToStreamTailerThread",
            "FileToStreamTailerThread",
            "Timer",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
            )
          )
        }

        // Exercise modifying build.mill
        for (i <- Range(0, 2)) {
          tester.modifyFile(tester.workspacePath / "build.mill", _ + "\n")

          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerManager" -> 1,
            "mill.javalib.JvmWorkerModule#worker cl" -> 2,
            "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 1
          )
          checkThreads(tester)(
            Seq(
            "HandleRunThread",
            "JsonArrayLogger mill-chrome-profile.json",
            "JsonArrayLogger mill-profile.json",
            "MillServerActionRunner",
            "MillServerTimeoutThread",
            "Process ID Checker Thread",
            "FileToStreamTailerThread",
            "FileToStreamTailerThread",
            "Timer",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
            )
          )

        }
        // Exercise modifying Foo.java, Foo.kt, Foo.scala
        for (i <- Range(0, 2)) {
          tester.modifyFile(tester.workspacePath / "hello-java/src/Foo.java", "//hello\n" + _)
          tester.modifyFile(tester.workspacePath / "hello-kotlin/src/Foo.kt", "//hello\n" + _)
          tester.modifyFile(tester.workspacePath / "hello-scala/src/Foo.scala", "//hello\n" + _)

          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerManager" -> 1,
            "mill.javalib.JvmWorkerModule#worker cl" -> 2,
            "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 1
          )
          checkThreads(tester)(
            Seq(
            "HandleRunThread",
            "JsonArrayLogger mill-chrome-profile.json",
            "JsonArrayLogger mill-profile.json",
            "MillServerActionRunner",
            "MillServerTimeoutThread",
            "Process ID Checker Thread",
            "FileToStreamTailerThread",
            "FileToStreamTailerThread",
            "Timer",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
            )
          )

        }

        // Make sure we can detect leaked classloaders and threads when the do happen
        tester.eval(("leakThreadClassloader"))
        checkClassloaders(tester)(
          "leaked classloader" -> 1,
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.kotlinlib.KotlinWorkerManager" -> 1,
          "mill.javalib.JvmWorkerModule#worker cl" -> 2,
          "mill.javalib.worker.JvmWorkerImpl#scalaCompilerCache.setup loader" -> 1
        )
        checkThreads(tester)(
          Seq(
          "HandleRunThread",
          "JsonArrayLogger mill-chrome-profile.json",
          "JsonArrayLogger mill-profile.json",
          "MillServerActionRunner",
          "MillServerTimeoutThread",
          "Process ID Checker Thread",
          "FileToStreamTailerThread",
          "FileToStreamTailerThread",
          "Timer",
          "leaked thread",
          "main",
          "prompt-logger-stream-pumper-thread",
          "proxyInputStreamThroughPumper"
          )
        )
      }
    }
  }
}
