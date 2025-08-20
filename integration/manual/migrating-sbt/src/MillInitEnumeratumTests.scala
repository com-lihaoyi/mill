package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitEnumeratumTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 2.12.20 2.13.16 3.3.5
  // sbt-crossproject 1.3.2
  def gitRepoUrl = "git@github.com:lloydmeta/enumeratum.git"
  def gitRepoBranch = "enumeratum-1.9.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "publish is disabled for Scala 3"
    }
  }
}
