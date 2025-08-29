package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleEhcache3Tests extends GitRepoIntegrationTestSuite {

  // gradle 7.2
  // custom dependency configurations
  // dependencies with version constraints
  // custom layout
  // custom repository
  // bom dependencies
  // modules with pom packaging
  // Junit4
  def gitRepoUrl = "https://github.com/ehcache/ehcache3.git"
  def gitRepoBranch = "v3.10.8"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      // https://docs.gradle.org/current/userguide/compatibility.html#java_runtime
      os.write(workspacePath / ".mill-jvm-version", "11")

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("ehcache-api.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("ehcache-api.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // custom dependency configurations not supported
      eval("ehcache-xml.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
