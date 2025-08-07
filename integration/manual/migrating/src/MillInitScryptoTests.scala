package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitScryptoTests extends GitRepoIntegrationTestSuite {

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
