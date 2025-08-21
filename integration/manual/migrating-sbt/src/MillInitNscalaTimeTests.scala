package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitNscalaTimeTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 2.11.12 2.12.20 2.13.15 3.3.4
  // single root module
  def gitRepoUrl = "https://github.com/nscala-time/nscala-time.git"
  def gitRepoBranch = "releases/3.0.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
