package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleSpotbugsTests extends GitRepoIntegrationTestSuite {

  // gradle 9.0.0
  // custom dependency configurations
  // dependencies with version constraints
  // custom layout
  // Junit5
  def gitRepoUrl = "https://github.com/spotbugs/spotbugs.git"
  def gitRepoBranch = "4.9.4"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
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

      // missing sources from custom layout
      eval("spotbugs-tests.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
