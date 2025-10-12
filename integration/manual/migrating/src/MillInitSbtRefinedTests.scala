package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtRefinedTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.7
      "https://github.com/fthomas/refined.git",
      "v0.11.3",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Requires manual fix for custom version ranges (3.0+/3.0-).
        |
        |modules.core.jvm[3.3.4].compile
        |[error] -- [E006] Not Found Error: .../refined/modules/core/shared/src/main/scala/eu/timepit/refined/internal/RefinePartiallyApplied.scala:12:51 
        |""".stripMargin
    }
  }
}
