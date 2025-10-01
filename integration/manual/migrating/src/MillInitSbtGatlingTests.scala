package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtGatlingTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.11
  // Scala version 2.13.16

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/gatling/gatling.git",
      "v3.14.3"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // io.gatling.charts.result.reader.LogFileReaderSpec fails due to missing resource bundle
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
      // ByteBufUtils.java:33: error: illegal cyclic reference involving class Utf8ByteBufCharsetDecoder
      eval(
        "gatling-netty-util.scalaDocGenerated",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> false
    }
  }
}
