package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtAirstreamTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.7
      "https://github.com/raquo/Airstream.git",
      "v17.2.1",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Requires support for jsEnvConfig.
        |
        |[2.13.16].test.testForked 3 tests failed: 
        |""".stripMargin
    }
  }
}
