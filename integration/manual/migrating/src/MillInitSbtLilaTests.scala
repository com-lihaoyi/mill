package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtLilaTests extends GitRepoIntegrationTestSuite {

  // sbt 1.11.3
  // Scala version 3.7.2

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/lichess-org/lila.git",
      "master"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // non-standard layout
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
