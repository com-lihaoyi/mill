package mill.integration

import coursier.Resolve
import coursier.cache.FileCache
import coursier.jvm.{JvmCache, JvmChannel, JvmIndex}
import mill.testkit.UtestIntegrationTestSuite

import utest._

object NoJavaBootstrapTests extends UtestIntegrationTestSuite {
  // Don't propagate `JAVA_HOME` to this test suite, because we want to exercise
  // the code path where `JAVA_HOME` is not present during bootstrapping
  override def propagateJavaHome = false

  // Compute the expected JVM version from the coursier index
  // In PRs bumping the index version, the JVM version might differ from the
  // one of the Mill process running the tests
  private lazy val expectedJavaVersion = {
    val cache = FileCache()
    val index = JvmIndex.load(
      cache = cache,
      repositories = Resolve.defaultRepositories,
      indexChannel = JvmChannel.module(
        JvmChannel.centralModule(),
        version = mill.api.BuildInfo.coursierJvmIndexVersion
      )
    )
    val jvmCache = JvmCache().withIndex(index)

    val entry = cache.logger.use(jvmCache.entries(mill.client.BuildInfo.defaultJvmId))
      .unsafeRun()(using cache.ec)
      .left.map(err => sys.error(err))
      .merge
      .last

    entry.version
  }

  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      os.remove(tester.workspacePath / ".mill-jvm-version")
      // The Mill server process should use the default Mill Java version,
      // even without the `.mill-jvm-version` present
      //
      // Force Mill client to ignore any system `java` installation, to make sure
      // this tests works reliably regardless of what is installed on the system
      val res1 = eval("foo", stderr = os.Inherit)

      assert(res1.out == expectedJavaVersion)

      // Any `JavaModule`s run from the Mill server should also inherit
      // the default Mill Java version from it
      val res2 = eval("bar.run", stderr = os.Inherit)

      assert(res2.out == s"Hello World! $expectedJavaVersion")
    }
  }
}
