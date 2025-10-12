package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScryptoTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.7
      "https://github.com/input-output-hk/scrypto.git",
      "v3.1.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Requires support for ScalablyTypedConverterGenSourcePlugin
        |
        |js[2.13.16].compile
        |[error] .../scrypto/js/src/main/scala/scorex/crypto/hash/Platform.scala:3:15: object nobleHashes is not a member of package scorex
        |""".stripMargin
    }
  }
}
