package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtCatsTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.7
      "https://github.com/typelevel/cats.git",
      "v2.13.0"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // missing generated sources
      eval(
        "kernel.jvm[2.13.16].compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false
    }
  }
}
