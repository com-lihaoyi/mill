package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScoptTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:scopt/scopt.git"
  def gitRepoBranch = "v4.1.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "requires verify test framework"
    }
  }
}
