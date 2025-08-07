package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScala3Tests extends GitRepoIntegrationTestSuite {

  // sbt 1.11.0
  // Scala version 3.7.0
  def gitRepoUrl = "git@github.com:scala/scala3.git"
  def gitRepoBranch = "3.7.1"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      "project uses non-Maven layout (nothing is compiled)"
    }
  }
}
