package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtLilaTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.11.3
      // Play framework
      // non-standard layout
      "https://github.com/lichess-org/lila.git",
      "master",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(("init", "--merge"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      """Requires support for defining test as plain JavaModule.
        |
        |[error] 57 │        def moduleDeps = super.moduleDeps ++ Seq(build.modules.coreI18n.test)
        |[error]    │                                                                        ^^^^
        |[error]    │value test is not a member of object build_.package_.modules.coreI18n
        |""".stripMargin
    }
  }
}
