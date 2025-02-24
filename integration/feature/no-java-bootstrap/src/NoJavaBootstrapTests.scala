package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object NoJavaBootstrapTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      os.remove(tester.workspacePath / ".mill-jvm-version")

      // The Mill server process should use the default Mill Java version,
      // evenw without the `.mill-jvm-version` present
      val res1 = eval("foo")
      assert(res1.out == "17.0.14")

      // Any `JavaModule`s run from the Mill server should also inherit
      // the default Mill Java version from it
      val res2 = eval("bar.run")
      assert(res2.out == "Hello World! 17.0.14")
    }
  }
}
