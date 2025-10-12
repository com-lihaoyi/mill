package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenErrorProneTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // maven-toolchains-plugin
      "https://github.com/google/error-prone.git",
      "v2.41.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("__.showModuleDeps"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """JDK 25 is missing in coursier index?
        |
        |javaHome coursier.jvm.JvmCache$JvmNotFoundInIndex: JVM zulu:25 not found in index: No zulu version matching '25' found
        |""".stripMargin
    }
  }
}
