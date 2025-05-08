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
    val res = tester.eval(("show", "countClassLoaders"))

    val read = upickle.default.read[SortedMap[String, Int]](res.out)
    val expected = SortedMap(kvs*)
    // pprint.log(read)
    // pprint.log(expected)
    assert(read == expected)
  }

  def checkThreads(tester: IntegrationTester)(expected: String*) = {
    val out = tester.eval(("show", "countThreads")).out
    val read = upickle.default.read[Seq[String]](out)
    val filtered = read.filter {
      case s"coursier-pool-$_" => false
      case s"scala-execution-context-$_" => false
      case _ => true
    }
    // pprint.log(read)
    // pprint.log(expected)
    assert(filtered == expected)
  }

  val tests: Tests = Tests {
    test - integrationTest { tester =>
      if (clientServerMode) {
        checkClassloaders(tester)(
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.codesig.ExternalSummary.apply upstreamClassloader" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 1,
          "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
        )
        checkThreads(tester)(
          "HandleRunThread",
          "MillServerActionRunner",
          "MillSocketTimeoutInterruptThread",
          "Process ID Checker Thread",
          "Tail",
          "Tail",
          "execution-contexts-threadpool-thread",
          "main",
          "prompt-logger-stream-pumper-thread",
          "proxyInputStreamThroughPumper"
        )

        // Exercise clean compile all
        for (i <- Range(0, 2)) {
          tester.eval(("show", "clean"))
          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.codesig.ExternalSummary.apply upstreamClassloader" -> 1,
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerFactory#setup cl" -> 1,
            "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
            "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 2
          )
          checkThreads(tester)(
            "HandleRunThread",
            "MillServerActionRunner",
            "MillSocketTimeoutInterruptThread",
            "Process ID Checker Thread",
            "Tail",
            "Tail",
            "execution-contexts-threadpool-thread",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
          )

        }

        // Exercise no-op compile all
        for (i <- Range(0, 2)) {
          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.codesig.ExternalSummary.apply upstreamClassloader" -> 1,
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerFactory#setup cl" -> 1,
            "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
            "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 2
          )
          checkThreads(tester)(
            "HandleRunThread",
            "MillServerActionRunner",
            "MillSocketTimeoutInterruptThread",
            "Process ID Checker Thread",
            "Tail",
            "Tail",
            "execution-contexts-threadpool-thread",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
          )

        }

        // Exercise post-shutdown

        tester.eval(("shutdown"))
        checkClassloaders(tester)(
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 1
        )
        checkThreads(tester)(
          "HandleRunThread",
          "MillServerActionRunner",
          "MillSocketTimeoutInterruptThread",
          "Process ID Checker Thread",
          "Tail",
          "Tail",
          "execution-contexts-threadpool-thread",
          "main",
          "prompt-logger-stream-pumper-thread",
          "proxyInputStreamThroughPumper"
        )

        // Exercise clean compile all post-shutdown
        for (i <- Range(0, 2)) {
          tester.eval(("show", "clean"))
          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerFactory#setup cl" -> 1,
            "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
            "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
          )
          checkThreads(tester)(
            "HandleRunThread",
            "MillServerActionRunner",
            "MillSocketTimeoutInterruptThread",
            "Process ID Checker Thread",
            "Tail",
            "Tail",
            "execution-contexts-threadpool-thread",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
          )
        }

        // Exercise modifying build.mill
        for (i <- Range(0, 2)) {
          tester.modifyFile(tester.workspacePath / "build.mill", "\n" + _)

          tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerFactory#setup cl" -> 1,
            "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
            "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
          )
          checkThreads(tester)(
            "HandleRunThread",
            "MillServerActionRunner",
            "MillSocketTimeoutInterruptThread",
            "Process ID Checker Thread",
            "Tail",
            "Tail",
            "execution-contexts-threadpool-thread",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
          )

        }
        // Exercise modifying Foo.java, Foo.kt, Foo.scala
        for (i <- Range(0, 2)) {
          tester.modifyFile(tester.workspacePath / "hello-java/src/Foo.java", "//hello\n" + _)
          tester.modifyFile(tester.workspacePath / "hello-kotlin/src/Foo.kt", "//hello\n" + _)
          tester.modifyFile(tester.workspacePath / "hello-scala/src/Foo.scala", "//hello\n" + _)

          val res = tester.eval(("show", "__.compile"))
          checkClassloaders(tester)(
            "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
            "mill.kotlinlib.KotlinWorkerFactory#setup cl" -> 1,
            "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
            "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
          )
          checkThreads(tester)(
            "HandleRunThread",
            "MillServerActionRunner",
            "MillSocketTimeoutInterruptThread",
            "Process ID Checker Thread",
            "Tail",
            "Tail",
            "execution-contexts-threadpool-thread",
            "main",
            "prompt-logger-stream-pumper-thread",
            "proxyInputStreamThroughPumper"
          )

        }

        // Make sure we can detect leaked classloaders and threads when the do happen
        tester.eval(("leakThreadClassloader"))
        checkClassloaders(tester)(
          "leaked classloader" -> 1,
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.kotlinlib.KotlinWorkerFactory#setup cl" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
          "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
        )
        checkThreads(tester)(
          "HandleRunThread",
          "MillServerActionRunner",
          "MillSocketTimeoutInterruptThread",
          "Process ID Checker Thread",
          "Tail",
          "Tail",
          "execution-contexts-threadpool-thread",
          "leaked thread",
          "main",
          "prompt-logger-stream-pumper-thread",
          "proxyInputStreamThroughPumper"
        )
      }
    }
  }
}
