package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitErrorProneTests extends GitRepoIntegrationTestSuite {

  def gitRepoUrl = "https://github.com/google/error-prone.git"
  def gitRepoBranch = "v2.41.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      os.write(workspacePath / ".mill-jvm-version", "17")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires support for javac annotation processors
      eval("core.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
