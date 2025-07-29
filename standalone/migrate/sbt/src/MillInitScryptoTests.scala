package mill.standalone

import utest.*

object MillInitScryptoTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:input-output-hk/scrypto.git"
  def gitRepoBranch = "v3.1.0"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("jvm[2.12.20].compile").isSuccess ==> true
      eval("jvm[2.12.20].publishLocal").isSuccess ==> true
      eval("jvm[2.12.20].test").isSuccess ==> true
      eval("js[2.13.16].compile").isSuccess ==> false

      "js module requires ScalablyTypedConverterGenSourcePlugin"
    }
  }
}
