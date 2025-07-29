package mill.standalone

import utest.*

object MillInitAirstreamTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:raquo/Airstream.git"
  def gitRepoBranch = "v17.2.1"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("[_].compile").isSuccess ==> true
      eval("[_].publishLocal").isSuccess ==> true
      eval("[2.13.16].test.compile").isSuccess ==> true
      eval("[2.13.16].test").isSuccess ==> false

      "test requires jsEnv setting"
    }
  }
}
