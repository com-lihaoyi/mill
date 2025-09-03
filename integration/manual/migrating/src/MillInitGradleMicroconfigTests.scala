package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleMicroconfigTests extends GitRepoIntegrationTestSuite {

  // gradle 8.10.1
  // uses spring-boot-dependencies BOM
  // Junit5
  def gitRepoUrl = "https://github.com/microconfig/microconfig.git"
  def gitRepoBranch = "v4.9.5"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval(("init", "-u"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
