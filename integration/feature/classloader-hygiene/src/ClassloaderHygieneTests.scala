package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import scala.collection.SortedMap
import utest._

/**
 * Run through some common scenarios on a simple Mill build to ensure we don't leak
 * classloaders.
 *
 * Runs through most scenarios twice to make sure the number of open classloaders
 * doesn't increase when nothing has changed.
 */
object ClassloaderHygieneTests extends UtestIntegrationTestSuite {
  def checkOutContents(out: String)(kvs: (String, Int)*) = {
    val read = upickle.default.read[SortedMap[String, Int]](out)
    val expected = SortedMap(kvs*)
    // pprint.log(read)
    // pprint.log(expected)
    assert(read == expected)
  }
  val tests: Tests = Tests {
    test - integrationTest { tester =>

      val out0 = tester.eval(("show", "countClassLoaders"))
      checkOutContents(out0.out)(
        "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
        "mill.codesig.ExternalSummary.apply upstreamClassloader" -> 1,
        "mill.meta.ScalaCompilerWorker.reflectUnsafe cl" -> 1,
        "mill.scalalib.JvmWorkerModule#worker cl" -> 1,
        "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
      )

      for (i <- Range(0, 2)) {
        tester.eval(("show", "clean"))
        tester.eval(("show", "__.compile"))
        val out1 = tester.eval(("show", "countClassLoaders"))
        checkOutContents(out1.out)(
          "mill.codesig.ExternalSummary.apply upstreamClassloader" -> 1,
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.kotlinlib.KotlinModule#kotlinWorkerClassLoader" -> 1,
          "mill.meta.ScalaCompilerWorker.reflectUnsafe cl" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
          "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 2
        )
      }

      for (i <- Range(0, 2)) {
        tester.eval(("show", "__.compile"))
        val out2 = tester.eval(("show", "countClassLoaders"))
        checkOutContents(out2.out)(
          "mill.codesig.ExternalSummary.apply upstreamClassloader" -> 1,
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.kotlinlib.KotlinModule#kotlinWorkerClassLoader" -> 1,
          "mill.meta.ScalaCompilerWorker.reflectUnsafe cl" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
          "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 2
        )
      }

      for (i <- Range(0, 2)) {
        tester.eval(("shutdown"))
        val out4 = tester.eval(("show", "countClassLoaders"))
        checkOutContents(out4.out)(
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.meta.ScalaCompilerWorker.reflectUnsafe cl" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 1
        )
      }

      tester.eval(("show", "clean"))
      tester.eval(("show", "__.compile"))
      val out5 = tester.eval(("show", "countClassLoaders"))
      checkOutContents(out5.out)(
        "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
        "mill.kotlinlib.KotlinModule#kotlinWorkerClassLoader" -> 1,
        "mill.meta.ScalaCompilerWorker.reflectUnsafe cl" -> 1,
        "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
        "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
      )

      for (i <- Range(0, 2)) {
        tester.modifyFile(tester.workspacePath / "build.mill", "\n" + _)

        tester.eval(("show", "__.compile"))
        val out6 = tester.eval(("show", "countClassLoaders"))
        checkOutContents(out6.out)(
          "mill.daemon.MillBuildBootstrap#processRunClasspath classLoader cl" -> 1,
          "mill.kotlinlib.KotlinModule#kotlinWorkerClassLoader" -> 1,
          "mill.meta.ScalaCompilerWorker.reflectUnsafe cl" -> 1,
          "mill.scalalib.JvmWorkerModule#worker cl" -> 2,
          "mill.scalalib.worker.JvmWorkerImpl#getCachedClassLoader cl" -> 1
        )
      }
    }
  }
}
