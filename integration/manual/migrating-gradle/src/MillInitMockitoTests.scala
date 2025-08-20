package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMockitoTests extends GitRepoIntegrationTestSuite {

  // gradle 8.14.2
  // contains BOM module
  def gitRepoUrl = "git@github.com:mockito/mockito.git"
  def gitRepoBranch = "v5.19.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires support for Java modules
      eval("mockito-core.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
