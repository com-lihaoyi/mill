package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitNscalaTimeTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:nscala-time/nscala-time.git"
  def gitRepoBranch = "releases/3.0.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[2.11.12].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[2.11.12].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[2.11.12].publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
