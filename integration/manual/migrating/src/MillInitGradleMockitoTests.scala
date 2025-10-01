package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleMockitoTests extends GitRepoIntegrationTestSuite {

  // gradle 8.14.2
  // contains BOM module

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/mockito/mockito.git",
      "v5.19.0"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // unlike Gradle, Mill does not autoconfigure javac --module-path"
      eval("mockito-core.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
