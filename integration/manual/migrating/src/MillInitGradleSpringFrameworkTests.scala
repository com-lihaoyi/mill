package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleSpringFrameworkTests extends GitRepoIntegrationTestSuite {

  // Gradle 8.14.3
  // custom repository
  // dependencies with version constraints
  // BOM modules
  // uses errorprone

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/spring-projects/spring-framework.git",
      "v6.2.11",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      // JDK 17 is for release but JDK 24 is required for javadoc
      eval(
        ("init", "--gradle-jvm-id", "24"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // local BOM module not supported
      eval("spring-core.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
