package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScalaPBTests extends GitRepoIntegrationTestSuite {

  // sbt 1.11.2

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/scalapb/ScalaPB.git",
      "v0.11.19"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires sbt-projectmatrix
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
