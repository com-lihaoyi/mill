package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenJansiTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/fusesource/jansi.git",
      "jansi-2.4.2",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      "compile, test, publishLocal succeed."
    }
  }
}
