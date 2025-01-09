package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object VersionChangeTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._
      // Make sure the simplest case where we have a single task calling a single helper
      // method is properly invalidated when either the task body, or the helper method's body
      // is changed, or something changed in the constructor
      val javaVersion1 = eval(("show", "javaVersion"))
      assert(javaVersion1.out == "\"17.0.6\"")

      os.write(workspacePath / ".mill-jvm-version", "temurin:19.0.2")
      os.remove.all(workspacePath / "out")

      val javaVersion2 = eval(("show", "javaVersion"))
      assert(javaVersion2.out == "\"19.0.2\"")

    }
  }
}
