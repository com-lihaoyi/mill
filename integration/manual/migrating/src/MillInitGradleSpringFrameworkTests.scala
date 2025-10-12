package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleSpringFrameworkTests extends GitRepoIntegrationTestSuite {

  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 8.14.3
      // ErrorProne
      "https://github.com/spring-projects/spring-framework.git",
      "v6.2.11",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(
        ("init", "--gradle-jvm-id", "24"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      s"""Requires support for BomModule (framework-platform).
         |
         |spring-instrument.resolvedMvnDeps java.lang.RuntimeException: 
         |Resolution failed for 1 modules:
         |--------------------------------------------
         |  com.google.code.findbugs:jsr305: 
         |""".stripMargin
    }
  }
}
