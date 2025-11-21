package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object SystemJavaTests extends UtestIntegrationTestSuite {
  override def propagateJavaHome = false
  val tests: Tests = Tests {

    test("header") - integrationTest { tester =>
      val defaultVersion = tester.eval("printJavaVersion")
      assert(defaultVersion.out.contains("21.0.9"))

      val defaultHome = tester.eval("printJavaHome")
      tester.modifyFile(
        tester.workspacePath / "build.mill",
        s => "//| mill-jvm-version: system\n" + s
      )
      val updatedHome = tester.eval("printJavaHome")
      assert(updatedHome.out != defaultHome.out)
    }

  }
}
