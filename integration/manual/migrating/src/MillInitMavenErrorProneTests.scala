package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenErrorProneTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // uses maven-toolchains-plugin
      "https://github.com/google/error-prone.git",
      "v2.41.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("__.showModuleDeps"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // JDK 25 is missing in the coursier-jvm index?
      eval("core.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
