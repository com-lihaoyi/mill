package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleMicroconfigTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    // Gradle 8.10.1
    // depends on spring-boot-dependencies BOM
    test - integrationTestGitRepo(
      "https://github.com/microconfig/microconfig.git",
      "v4.9.5",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(
        ("init", "--gradle-jvm-id", "11"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
