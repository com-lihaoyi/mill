package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitPCollectionsTests extends GitRepoIntegrationTestSuite {

  // gradle 8.14.3
  // single module
  // Junit5
  def gitRepoUrl = "https://github.com/hrldcpr/pcollections.git"
  def gitRepoBranch = "v5.0.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires non-LTS JDK9
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
