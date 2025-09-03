package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleAsmTests extends GitRepoIntegrationTestSuite {

  // gradle 8.3
  def gitRepoUrl = "https://gitlab.ow2.org/asm/asm.git"
  def gitRepoBranch = "ASM_9_8"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      os.write(workspacePath / ".mill-jvm-version", "11")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("asm.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("asm.publishLocal", stdout = os.Inherit, stderr = os.Inherit)

      // modules are tested with asm-test module?
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
