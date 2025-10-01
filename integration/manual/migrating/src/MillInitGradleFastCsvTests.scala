package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleFastCsvTests extends GitRepoIntegrationTestSuite {

  // gradle 9.0.0-rc-1
  // Junit5
  // uses ErrorProne

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/osiegmar/FastCSV.git",
      "v4.0.0"
    ) { tester =>
      import tester.*

      eval(
        ("init", "--gradle-jvm-id", "17"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("lib.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("lib.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // unlike Gradle, Mill does not autoconfigure javac --module-path"
      eval("example.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      // junit-platform-launcher version is autoconfigured by Gradle
      eval("lib.test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
