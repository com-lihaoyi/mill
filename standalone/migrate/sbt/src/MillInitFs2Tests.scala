package mill.standalone

import utest.*

object MillInitFs2Tests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:typelevel/fs2.git"
  def gitRepoBranch = "v3.12.0"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("core.jvm[2.13.16].compile").isSuccess ==> true
      eval("core.jvm[2.13.16].publishLocal").isSuccess ==> true
      eval("core.jvm[2.13.16].test").isSuccess ==> false

      "requires cats-effect-testkit"
    }
  }
}
