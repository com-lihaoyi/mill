package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleFastCsvTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // Gradle 9.0.0-rc-1
      // spotless, checkstyle, pmd plugins
      // ErrorProne
      "https://github.com/osiegmar/FastCSV.git",
      "v4.0.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      // https://fastcsv.org/guides/contribution/
      // JVM 25 is missing in Coursier index?
      eval(
        ("init", "--gradle-jvm-id", "24"),
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Requires manual fix for javacOptions.
        |Gradle auto-configures -proc:none when -processorpath is undefined/empty.
        |
        |lib.compile
        |[warn] No processor claimed any of these annotations: de.siegmar.fastcsv/de.siegmar.fastcsv.util.Nullable
        |[error] warnings found and -Werror specified
        |""".stripMargin
    }
  }
}
