package mill.standalone

import utest.*

object MillInitGatlingTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:gatling/gatling.git"
  def gitRepoBranch = "v3.13.5"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("__.compile").isSuccess ==> true
      eval("__.publishLocal").isSuccess ==> false
      eval("__.test").isSuccess ==> false

      "scaladoc generation and some tests fail"
    }
  }
}
