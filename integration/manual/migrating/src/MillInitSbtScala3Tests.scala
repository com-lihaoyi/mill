package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScala3Tests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.11.0
      "https://github.com/scala/scala3.git",
      "3.7.1",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(("init", "--merge"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "Highly customized build with multiple projects using the same base directory."
    }
  }
}
