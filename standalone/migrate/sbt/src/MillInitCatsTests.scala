package mill.standalone

import utest.*

object MillInitCatsTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:typelevel/cats.git"
  def gitRepoBranch = "v2.13.0"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc", "--no-unify")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("kernel-laws.jvm[2.13.16].compile").isSuccess ==> false

      "missing generated sources"
    }
  }
}
