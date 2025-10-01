package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradlePCollectionsTests extends GitRepoIntegrationTestSuite {

  // gradle 8.14.3
  // single module
  // Junit5

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/hrldcpr/pcollections.git",
      "v5.0.0"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // typos in class names
      eval("javadocGenerated", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
      // junit-platform-launcher version is autoconfigured by Gradle
      eval("test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
