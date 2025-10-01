package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtEnumeratumTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 2.12.20 2.13.16 3.3.5
  // sbt-crossproject 1.3.2
  // publish is disabled for Scala 3 in enumeratum-json4s module
  // configures moduleDeps dynamically using system property

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/lloydmeta/enumeratum.git",
      "enumeratum-1.9.0"
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "__.compile"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // custom source directory not supported
      eval("macros.__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
      // invalid cross module dependency on enumeratum-play
      eval("benchmarking.__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
