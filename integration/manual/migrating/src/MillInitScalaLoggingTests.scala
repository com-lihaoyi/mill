package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScalaLoggingTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "git@github.com:lightbend-labs/scala-logging.git"
  def gitRepoBranch = "v3.9.5"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
