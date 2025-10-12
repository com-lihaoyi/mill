package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScalaPBTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.11.2
      "https://github.com/scalapb/ScalaPB.git",
      "v0.11.19",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      // requires support for converting sbt-projectmatrix platform modules
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "Requires support for sbt-projectmatrix."
    }
  }
}
