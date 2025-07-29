package mill.standalone

import utest.*

object MillInitEnumeratumTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:lloydmeta/enumeratum.git"
  def gitRepoBranch = "enumeratum-1.9.0"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval(("__.compile", "_")).isSuccess ==> false

      "cross Scala versions mismatch in benchmark -> enumeratum-play dependency"
    }
  }
}
