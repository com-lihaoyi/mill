package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenCheckstyleTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // has .mvn/jvm.config
      "https://github.com/checkstyle/checkstyle.git",
      "checkstyle-11.0.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // missing generated sources
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
