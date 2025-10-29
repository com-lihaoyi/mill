package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object SystemJavaTests extends UtestIntegrationTestSuite {
  override def propagateJavaHome = false
  val tests: Tests = Tests {

    test("header") - integrationTest { tester =>
      val defaultVersion = tester.eval("printJavaVersion")
      assert(defaultVersion.out.contains("21.0.8"))

      val defaultHome = tester.eval("printJavaHome")
      tester.modifyFile(
        tester.workspacePath / "build.mill",
        s => "//| mill-jvm-version: system\n" + s
      )
      val updatedHome = tester.eval("printJavaHome")
      pprint.log(updatedHome.out)
      pprint.log(defaultHome.out)
      assert(updatedHome.out != defaultHome.out)
    }

  }
}
