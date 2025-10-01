package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenNettyTests extends GitRepoIntegrationTestSuite {

  // maven 3.9.10
  // dynamic classifiers/properties

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/netty/netty.git",
      "netty-4.2.4.Final"
    ) { tester =>
      import tester.*

      eval(
        ("init", "--publish-properties"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("common.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // The project defines dependencies for junit-jupiter-* but junit-platform-launcher is
      // auto-added by Maven. In Mill, a legacy version of junit-platform-launcher gets added, as a
      // transitive dependency of com.github.sbt.junit:junit-interface, resulting in a conflict.
      eval("common.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
