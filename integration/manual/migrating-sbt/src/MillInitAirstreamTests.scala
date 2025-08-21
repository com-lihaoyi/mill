package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitAirstreamTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 3.3.3 2.13.16
  // single ScalaJS root module
  // scalajs-dom dependency
  def gitRepoUrl = "https://github.com/raquo/Airstream.git"
  def gitRepoBranch = "v17.2.1"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[2.13.16].test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[2.13.16].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "test requires jsEnv setting"
    }
  }
}
