package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenNettyTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // maven-enforcer-plugin, maven-checkstyle-plugin
      // uses os-maven-plugin to configure dependencies
      // has modules that skip publish
      "https://github.com/netty/netty.git",
      "netty-4.2.6.Final",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Build functionality is limited due to unresolved (dynamically configured) dependencies.
        |- test discovery fails due to version mismatch between Junit5 engine and platform dependencies
        |- requires conversion support for javadocOptions
        |""".stripMargin
    }
  }
}
