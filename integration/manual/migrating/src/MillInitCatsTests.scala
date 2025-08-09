package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitCatsTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 2.12.20 2.13.16 3.3.4
  // sources for cross Scala version ranges
  // sbt-crossproject 1.3.2
  // different CrossType modules
  def gitRepoUrl = "git@github.com:typelevel/cats.git"
  def gitRepoBranch = "v2.13.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "kernel-laws.jvm[_].compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false

      "missing generated sources"
    }
  }
}
