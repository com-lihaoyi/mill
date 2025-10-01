package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleEhcache3Tests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 7.2
      "https://github.com/ehcache/ehcache3.git",
      "v3.10.8",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval(
        ("init", "--gradle-jvm-id", "11"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // Gradle autoconfigures javac option -proc:none when -processorpath is not provided
      eval("ehcache-api.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
