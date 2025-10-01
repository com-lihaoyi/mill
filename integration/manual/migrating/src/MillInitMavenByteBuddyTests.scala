package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenByteBuddyTests extends GitRepoIntegrationTestSuite {

  // maven 3.9.9
  // contains Android module
  // contains C sources for JNA
  // Junit4

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/raphw/byte-buddy.git",
      "byte-buddy-1.17.7"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("byte-buddy-agent.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "byte-buddy-agent.publishLocal",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true

      // requires native compilation support
      eval(
        "byte-buddy-agent.test.compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false
    }
  }
}
