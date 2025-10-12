package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleEhcache3Tests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 7.2
      // checkstyle, spotbugs plugins
      // highly customized build
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

      """Requires manual fix for javacOptions.
        |Gradle auto-configures -proc:none when -processorpath is undefined/empty.
        |
        |ehcache-api.compile
        |[warn] No processor claimed any of these annotations: org.ehcache.spi.service.PluralService,org.ehcache.javadoc.PublicApi
        |[error] warnings found and -Werror specified
        |""".stripMargin
    }
  }
}
