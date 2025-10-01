package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtRefinedTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 2.12.20 2.13.15 3.3.4
  // sbt-crossproject 1.3.2

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/fthomas/refined.git",
      "v0.11.3"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // custom version range source roots 3.0+/3.0- not supported
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
