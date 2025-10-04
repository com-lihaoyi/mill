package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtNscalaTimeTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.7
      "https://github.com/nscala-time/nscala-time.git",
      "releases/3.0.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__,showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
