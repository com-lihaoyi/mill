package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleSpotbugsTests extends GitRepoIntegrationTestSuite {

  // gradle 9.0.0
  // custom dependency configurations
  // dependencies with version constraints
  // custom layout
  // Junit5

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/spotbugs/spotbugs.git",
      "4.9.4"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
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

      // custom source directory not supported
      eval("spotbugs-tests.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
