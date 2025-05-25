package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object VersionChangeTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._
      val javaVersion1 = eval(("show", "javaVersion"))
      assert(!javaVersion1.out.contains("19.0.2"))

      os.write.over(workspacePath / ".mill-jvm-version", "temurin:19.0.2")

      val javaVersion2 = eval(("show", "javaVersion"))
      assert(javaVersion2.out.contains("\"19.0.2\""))

    }
  }
}
