package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitLilaTests extends GitRepoIntegrationTestSuite {

  // sbt 1.11.3
  // Scala version 3.7.2
  def gitRepoUrl = "git@github.com:lichess-org/lila.git"
  def gitRepoBranch = "master"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      """non-standard SBT layout
        |requires sbt-play""".stripMargin
    }
  }
}
