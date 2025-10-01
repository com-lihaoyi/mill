package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtEnumeratumTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    // sbt 1.10.7
    // publish is disabled for Scala 3 in enumeratum-json4s module
    // configures moduleDeps dynamically using system property
    test - integrationTestGitRepo(
      "https://github.com/lloydmeta/enumeratum.git",
      "enumeratum-1.9.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // custom source directory not supported
      eval("macros.__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
      // invalid cross module dependency on enumeratum-play
      eval("benchmarking.__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false
    }
  }
}
