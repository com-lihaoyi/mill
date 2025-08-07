package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitEnumeratumTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:lloydmeta/enumeratum.git"
  def gitRepoBranch = "enumeratum-1.9.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("__.compile", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "publish disabled for Scala 3 https://github.com/json4s/json4s/issues/1035"
    }
  }
}
