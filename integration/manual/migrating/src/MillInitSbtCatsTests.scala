package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtCatsTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.7
      "https://github.com/typelevel/cats.git",
      "v2.13.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Missing generated sources.
        |
        |[error] .../kernel/src/main/scala-2.13+/cats/kernel/instances/AllInstances.scala:55:10: not found: type TupleInstances
        |""".stripMargin
    }
  }
}
