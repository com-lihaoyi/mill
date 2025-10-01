package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScala3Tests extends GitRepoIntegrationTestSuite {

  // sbt 1.11.0
  // Scala version 3.7.0

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/scala/scala3.git",
      "3.7.1"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // non-standard layout
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
