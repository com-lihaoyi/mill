package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleJCommanderTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    // Gradle 8.9
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
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // annotations defined in main-module cannot be used in test-module?
      eval("test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
