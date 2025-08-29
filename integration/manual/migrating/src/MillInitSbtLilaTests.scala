package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtLilaTests extends GitRepoIntegrationTestSuite {

  // sbt 1.11.3
  // Scala version 3.7.2
  def gitRepoUrl = "https://github.com/lichess-org/lila.git"
  def gitRepoBranch = "master"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // non-standard layout
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
