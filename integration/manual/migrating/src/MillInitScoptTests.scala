package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScoptTests extends GitRepoIntegrationTestSuite {

  // sbt 1.5.2
  // cross Scala versions 2.11.12 2.12.16 2.13.8 3.1.3
  // sbt-crossproject 1.0.0
  // single cross-platform/version root module
  def gitRepoUrl = "git@github.com:scopt/scopt.git"
  def gitRepoBranch = "v4.1.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "requires verify test framework"
    }
  }
}
