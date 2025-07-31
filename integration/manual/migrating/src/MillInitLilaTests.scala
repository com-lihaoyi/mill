package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitLilaTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:lichess-org/lila.git"
  def gitRepoBranch = "master"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "requires sbt-play and module dependency core.test does not exist"
    }
  }
}
