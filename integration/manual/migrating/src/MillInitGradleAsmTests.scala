package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleAsmTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 8.3
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
      eval("asm.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // tests are run using asm-test module?
      eval("asm.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      // custom sourceSets not supported
      eval(
        "tools.retrofitter.compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false
    }
  }
}
