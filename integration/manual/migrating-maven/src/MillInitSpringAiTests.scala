package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSpringAiTests extends GitRepoIntegrationTestSuite {

  // maven 3.8.6
  // custom repositories
  // contains BOM module
  // transitive test framework dependency
  def gitRepoUrl = "https://github.com/spring-projects/spring-ai.git"
  def gitRepoBranch = "v1.0.1"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      os.write(workspacePath / ".mill-jvm-version", "17")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "spring-ai-model.publishLocal",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true

      // malformed HTML
      eval(
        "spring-ai-commons.publishLocal",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false

      // no test module because Junit5 dependency is transitive via spring-boot-starter-test
      eval("spring-ai-model.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
