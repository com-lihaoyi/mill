package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitCatsTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:typelevel/cats.git"
  def gitRepoBranch = "v2.13.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        ("kernel-laws.jvm[_].compile", "_"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false

      "missing generated sources"
    }
  }
}
