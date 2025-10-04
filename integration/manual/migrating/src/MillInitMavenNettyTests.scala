package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenNettyTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // has modules that skip publish
      "https://github.com/netty/netty.git",
      "netty-4.2.6.Final",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("common.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // mismatch between junit-jupiter-api and junit-platform-launcher (from sbt jupiter-interface)
      eval("common.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
