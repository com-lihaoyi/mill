package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleSpotbugsTests extends GitRepoIntegrationTestSuite {

  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 9.0.0
      "https://github.com/spotbugs/spotbugs.git",
      "4.9.4",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval(
        ("init", "--gradle-jvm-id", "17"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "spotbugs-annotations.compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval(
        "spotbugs-annotations.publishLocal",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true

      // custom source folders not supported
      eval("spotbugs-tests.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
