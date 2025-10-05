package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenErrorProneTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // uses maven-toolchains-plugin
      // requires JDK 25 that is missing in the Coursier JVM index?
      "https://github.com/google/error-prone.git",
      "v2.41.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("__.showModuleDeps"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
