package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSpringFrameworkTests extends GitRepoIntegrationTestSuite {

  // gradle 8.14.3
  // custom repository
  // dependencies with version constraints
  // BOM modules
  // uses errorprone
  def gitRepoUrl = "https://github.com/spring-projects/spring-framework.git"
  def gitRepoBranch = "v6.2.10"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      os.write(workspacePath / ".mill-jvm-version", "17")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires support for dependency version constraints
      eval("spring-jcl.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
