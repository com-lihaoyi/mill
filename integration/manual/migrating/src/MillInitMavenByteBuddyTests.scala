package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenByteBuddyTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/raphw/byte-buddy.git",
      "byte-buddy-1.17.7",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      s"""Requires Android support.
         |
         |[error] .../byte-buddy-android-test/src/main/java/net/bytebuddy/android/test/TestActivity.java:59:25: package R does not exist
         |""".stripMargin
    }
  }
}
