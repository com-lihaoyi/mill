package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleFastCsvTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 9.0.0-rc-1, ErrorProne
      "https://github.com/osiegmar/FastCSV.git",
      "v4.0.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      // https://fastcsv.org/guides/contribution/
      // requires JVM 25 as per docs, but it is missing in the coursier index
      eval(
        ("init", "--gradle-jvm-id", "24"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // Gradle autoconfigures javac option -proc:none when -processorpath is not provided
      eval("lib.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
