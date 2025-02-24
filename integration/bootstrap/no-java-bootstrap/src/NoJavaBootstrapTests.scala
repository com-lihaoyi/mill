package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object NoJavaBootstrapTests extends UtestIntegrationTestSuite {
  // Don't propagate `JAVA_HOME` to this test suite, because we want to exercise
  // the code path where `JAVA_HOME` is not present during bootstrapping
  override def propagateJavaHome = false
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      os.remove(tester.workspacePath / ".mill-jvm-version")
      // The Mill server process should use the default Mill Java version,
      // even without the `.mill-jvm-version` present
      //
      // Force Mill client to ignore any system `java` installation, to make sure
      // this tests works reliably regardless of what is installed on the system
      val res1 = eval(
        "foo",
        env = Map("MILL_TEST_SUITE_IGNORE_SYSTEM_JAVA" -> "true"),
        stderr = os.Inherit
      )

      assert(res1.out == System.getProperty("java.version"))

      // Any `JavaModule`s run from the Mill server should also inherit
      // the default Mill Java version from it
      val res2 = eval(
        "bar.run",
        env = Map("MILL_TEST_SUITE_IGNORE_SYSTEM_JAVA" -> "true"),
        stderr = os.Inherit
      )

      assert(res2.out == s"Hello World! ${System.getProperty("java.version")}")
    }
  }
}
