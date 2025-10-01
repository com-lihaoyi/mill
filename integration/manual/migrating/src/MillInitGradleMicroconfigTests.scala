package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleMicroconfigTests extends GitRepoIntegrationTestSuite {

  // Gradle 8.10.1
  // uses spring-boot-dependencies BOM
  // Junit5

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/microconfig/microconfig.git",
      "v4.9.5",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("_.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("_.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // Gradle resolves versions for Junit5 dependencies using transitive BOM?
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
