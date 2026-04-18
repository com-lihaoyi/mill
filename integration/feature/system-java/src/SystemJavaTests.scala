package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object SystemJavaTests extends UtestIntegrationTestSuite {
  override def propagateJavaHome = false
  val tests: Tests = Tests {

    test("header") - integrationTest { tester =>
      def normalized(s: String) = s.trim

      val defaultVersion = tester.eval("printJavaVersion")
      assert(defaultVersion.out.contains("21.0.10"))

      val defaultHome = tester.eval("printJavaHome")
      tester.modifyFile(
        tester.workspacePath / "build.mill",
        s => "//| mill-jvm-version: system\n" + s
      )
      val updatedHome = tester.eval("printJavaHome")
      val expectedSystemHome = normalized(sys.props("java.home"))
      assert(
        normalized(updatedHome.out) == expectedSystemHome ||
          normalized(updatedHome.out) != normalized(defaultHome.out)
      )
    }

  }
}
