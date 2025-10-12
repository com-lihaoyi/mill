package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleJCommanderTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    // Gradle 8.9
    // TestNg 7.0.0
    test - integrationTestGitRepo(
      "https://github.com/cbeust/jcommander.git",
      "2.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(
        ("init", "--gradle-jvm-id", "11"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit)

      s"""test.compile
         |.../src/test/java/com/beust/jcommander/ParameterOrderTest.java:33:23: cannot find symbol
         |[66] [error]   symbol:   method order()
         |[66] [error]   location: @interface com.beust.jcommander.DynamicParameter
         |""".stripMargin
    }
  }
}
