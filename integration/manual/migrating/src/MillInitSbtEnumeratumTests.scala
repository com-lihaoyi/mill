package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtEnumeratumTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    // sbt 1.10.7
    test - integrationTestGitRepo(
      "https://github.com/lloydmeta/enumeratum.git",
      "enumeratum-1.9.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      "enumeratum-json4s is not publishable because publish is disabled for Scala 3"
    }
  }
}
