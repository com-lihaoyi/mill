package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenJodaBeansTests extends GitRepoIntegrationTestSuite {

  // single module
  def gitRepoUrl = "https://github.com/JodaOrg/joda-beans.git"
  def gitRepoBranch = "v2.11.1"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // malformed HTML
      eval("publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
