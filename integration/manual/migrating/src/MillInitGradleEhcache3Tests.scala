package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleEhcache3Tests extends GitRepoIntegrationTestSuite {

  // Gradle 7.2
  // custom dependency configurations
  // dependencies with version constraints
  // custom layout
  // custom repository
  // bom dependencies
  // modules with pom packaging
  // Junit4

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/ehcache/ehcache3.git",
      "v3.10.8",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      // Gradle 7.2 fails on JDK 17
      eval(
        ("init", "--gradle-jvm-id", "16"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // missing -proc:none in javacOptions
      // Gradle command: ./gradlew --no-daemon -Dorg.gradle.debug=true :ehcache-api:compileJava
      eval("ehcache-api.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
