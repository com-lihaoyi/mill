package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtGatlingTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.11
  // Scala version 2.13.16
  def gitRepoUrl = "https://github.com/gatling/gatling.git"
  def gitRepoBranch = "v3.14.3"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      // missing resource bundle
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
