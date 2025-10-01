package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleJCommanderTests extends GitRepoIntegrationTestSuite {

  // gradle 8.9
  // single module
  // testng 7.0.0

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/cbeust/jcommander.git",
      "2.0"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // annotations defined in main-module cannot be used in test-module?
      eval("test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
