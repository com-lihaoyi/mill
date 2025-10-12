package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleMockitoTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 8.14.2
      // checkstyle, spotless plugins
      "https://github.com/mockito/mockito.git",
      "v5.19.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(
        ("init", "--gradle-jvm-id", "17"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      s"""Requires manual fix for jvmId (11).
         |
         |mockito-core.compile
         |[error] java.lang.UnsupportedClassVersionError: com/google/errorprone/ErrorProneJavacPlugin has been compiled by a more recent version of the Java Runtime
         |""".stripMargin
    }
  }
}
