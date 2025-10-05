package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleAsmTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 8.3
      // spotless, checkstyle, pmd plugins
      // non-standard testing approach using asm-test module
      "https://gitlab.ow2.org/asm/asm.git",
      "ASM_9_8",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval(
        ("init", "--gradle-jvm-id", "11"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      """tools.retrofitter.compile: requires support for custom sources (of asm module)
        |""".stripMargin
    }
  }
}
