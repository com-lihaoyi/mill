package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleEhcache3Tests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 7.2
      // checkstyle, spotbugs plugins
      // highly customized build using several plugins
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
      s"""__.compile
         |  - requires support for dependencies from custom configurations
         |  - warnings reported as error due to -Werror
         |    For example, ehcache-api.compile fails with a warning related to annotation processor.
         |    The warning disappears if the jvmId override is removed.
         |""".stripMargin
    }
  }
}
