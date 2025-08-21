package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitFastCsvTests extends GitRepoIntegrationTestSuite {

  // gradle 9.0.0-rc-1
  // Junit5
  // uses ErrorProne
  def gitRepoUrl = "https://github.com/osiegmar/FastCSV.git"
  def gitRepoBranch = "v4.0.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      // from tasks.compileJava.options.release
      os.write(workspacePath / ".mill-jvm-version", "17")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("lib.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("lib.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires support for Java modules
      eval("example.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      // junit-platform-launcher version is set implicitly (tasks.test.useJunitPlatform())
      // https://docs.gradle.org/8.14.3/userguide/upgrading_version_8.html#test_framework_implementation_dependencies
      eval("lib.test.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
