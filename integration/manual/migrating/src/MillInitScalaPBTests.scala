package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScalaPBTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:scalapb/ScalaPB.git"
  def gitRepoBranch = "v0.11.19"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "requires sbt-projectmatrix"
    }
  }
}
