package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMockitoTests extends GitRepoIntegrationTestSuite {

  // gradle 8.14.2
  // contains BOM module
  def gitRepoUrl = "https://github.com/mockito/mockito.git"
  def gitRepoBranch = "v5.19.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      // project requires Java 11 but Gradle version in use requires Java 17
      os.write(workspacePath / ".mill-jvm-version", "17")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires javacOptions for Java modules
      eval("mockito-core.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
