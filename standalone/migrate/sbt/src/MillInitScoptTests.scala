package mill.standalone

import utest.*

object MillInitScoptTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:scopt/scopt.git"
  def gitRepoBranch = "v4.1.0"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("__.compile").isSuccess ==> true
      eval("__.publishLocal").isSuccess ==> true
      eval("__.test").isSuccess ==> false

      "requires verify test framework"
    }
  }
}
