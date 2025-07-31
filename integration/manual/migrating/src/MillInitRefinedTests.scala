package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitRefinedTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:fthomas/refined.git"
  def gitRepoBranch = "v0.11.3"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "modules.core.jvm[2.12.20].compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false

      "custom version range 3.0- not supported"
    }
  }
}
