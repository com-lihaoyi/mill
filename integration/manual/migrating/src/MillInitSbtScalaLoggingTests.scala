package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScalaLoggingTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.6.2
      "https://github.com/lightbend-labs/scala-logging.git",
      "v3.9.5",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
