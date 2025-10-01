package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtGatlingTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.11
      "https://github.com/gatling/gatling.git",
      "v3.14.3"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // missing resource bundle
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
      // illegal cyclic reference involving class Utf8ByteBufCharsetDecoder
      eval(
        "gatling-netty-util.scalaDocGenerated",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false
    }
  }
}
