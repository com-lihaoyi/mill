package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenCheckstyleTests extends GitRepoIntegrationTestSuite {

  // maven 3.9.6
  // .mvn/jvm.config
  // errorprone
  // Junit5
  // single module
  def gitRepoUrl = "https://github.com/checkstyle/checkstyle.git"
  def gitRepoBranch = "checkstyle-11.0.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      os.write(workspacePath / ".mill-jvm-version", "17")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // missing generated sources
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
