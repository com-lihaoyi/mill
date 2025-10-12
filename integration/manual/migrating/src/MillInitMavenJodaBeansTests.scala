package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenJodaBeansTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // maven-enforcer-plugin
      "https://github.com/JodaOrg/joda-beans.git",
      "v2.11.1",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      "Requires support for javadocOptions."
    }
  }
}
