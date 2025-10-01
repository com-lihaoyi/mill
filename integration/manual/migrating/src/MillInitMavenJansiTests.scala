package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenJansiTests extends GitRepoIntegrationTestSuite {

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/fusesource/jansi.git",
      "jansi-2.4.2"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      // Publish things locally, under a directory that shouldn't outlive the test,
      // so that we don't pollute the user's ~/.ivy2/local
      val ivy2Repo = tester.baseWorkspacePath / "ivy2Local"
      eval(
        ("publishLocal", "--localIvyRepo", ivy2Repo.toString),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
    }
  }
}
