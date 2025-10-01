package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtAirstreamTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 3.3.3 2.13.16
  // single ScalaJS root module
  // scalajs-dom dependency

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/raquo/Airstream.git",
      "v17.2.1",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[_].publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("[2.13.16].test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // test requires jsEnv setting
      eval("[2.13.16].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
