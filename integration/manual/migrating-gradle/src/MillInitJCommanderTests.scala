package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitJCommanderTests extends GitRepoIntegrationTestSuite {

  // gradle 8.9
  // single module
  // testng 7.0.0
  def gitRepoUrl = "https://github.com/cbeust/jcommander.git"
  def gitRepoBranch = "2.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      os.write(workspacePath / ".mill-jvm-version", "11")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // annotations defined in main-module cannot be used in test-module?
      eval("test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
