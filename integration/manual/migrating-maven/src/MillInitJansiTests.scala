package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitJansiTests extends GitRepoIntegrationTestSuite {

  // single module
  def gitRepoUrl = "https://github.com/fusesource/jansi.git"
  def gitRepoBranch = "jansi-2.4.2"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
