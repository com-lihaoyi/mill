package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenAntlr4Tests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // polyglot project
      "https://github.com/antlr/antlr4.git",
      "4.13.2",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // custom layout not supported
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
