package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitFs2Tests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:typelevel/fs2.git"
  def gitRepoBranch = "v3.12.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("core.jvm[2.13.16].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "core.jvm[2.13.16].publishLocal",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("core.jvm[2.13.16].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "requires cats-effect-testkit"
    }
  }
}
