package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenSpringAiTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // maven-checkstyle-plugin
      // BOM module
      "https://github.com/spring-projects/spring-ai.git",
      "v1.0.3",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      s"""Requires support for dependency resolution.
         |Maven is able to resolve dependencies from BOMs defined in dependencyManagement.
         |
         |spring-ai-commons.resolvedMvnDeps java.lang.RuntimeException: 
         |Resolution failed for 9 modules:
         |""".stripMargin
    }
  }
}
