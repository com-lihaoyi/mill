package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScryptoTests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.7
  // cross Scala versions 2.11.12 2.12.20, 2.13.16 3.3.5
  // sbt-crossproject 1.3.2
  // root is a cross-platform/version module
  def gitRepoUrl = "git@github.com:input-output-hk/scrypto.git"
  def gitRepoBranch = "v3.1.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("jvm[_].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("jvm[_].test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("jvm[_].publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("js[_].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> false

      "requires ScalablyTypedConverterGenSourcePlugin"
    }
  }
}
