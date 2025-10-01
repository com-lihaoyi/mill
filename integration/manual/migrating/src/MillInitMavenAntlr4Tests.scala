package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenAntlr4Tests extends GitRepoIntegrationTestSuite {

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/antlr/antlr4.git",
      "v4.11.1"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // custom layout not supported
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
