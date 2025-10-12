package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtGatlingTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.11
      "https://github.com/gatling/gatling.git",
      "v3.14.3",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Requires manual fix for jvmId.
        |
        |[error] .../gatling/gatling-http-client/src/test/java/io/gatling/http/client/test/TestUtils.java:30:34: cannot access org.eclipse.jetty.util.ssl.SslContextFactory
        |[error]   bad class file: .../org/eclipse/jetty/jetty-util/12.0.21/jetty-util-12.0.21.jar(/org/eclipse/jetty/util/ssl/SslContextFactory.class)
        |[error]     class file has wrong version 61.0, should be 55.0
        |""".stripMargin
    }
  }
}
